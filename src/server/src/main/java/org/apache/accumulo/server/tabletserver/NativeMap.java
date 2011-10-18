/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.accumulo.server.tabletserver;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IterationInterruptedException;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.accumulo.core.iterators.system.InterruptibleIterator;
import org.apache.accumulo.start.Platform;
import org.apache.log4j.Logger;


/**
 * This class stores data in a C++ map.  Doing this allows us to 
 * store more in memory and avoid pauses caused by Java GC.
 * 
 * The strategy for dealing with native memory allocated for the
 * native map is that java code using the native map should call
 * delete() as soon as it is finished using the native map. When
 * the NativeMap object is garbage collected its native resources
 * will be released if needed.  However waiting for java GC would
 * be a mistake for long lived NativeMaps.  Long lived objects 
 * are not garbage collected quickly, therefore a process could
 * easily use too much memory.  
 *  
 */

public class NativeMap implements Iterable<Map.Entry<Key, Value>>{
	
	private static final Logger log = Logger.getLogger(NativeMap.class);
	
	private long nmPointer;

	private ReadWriteLock rwLock;
	private Lock rlock;
	private Lock wlock;
	
	int modCount = 0;

	private static native long createNM();
	//private static native void putNM(long nmPointer, byte[] kd, int cfo, int cqo, int cvo, int tl, long ts, boolean del, byte[] value);
	
	private static native void singleUpdate(long nmPointer, byte[] row, byte cf[], byte cq[], byte cv[], long ts, boolean del, byte[] value, int mutationCount);
	
	private static native long startUpdate(long nmPointer, byte[] row);
	private static native void update(long nmPointer, long updateID, byte cf[], byte cq[], byte cv[], long ts, boolean del, byte[] value, int mutationCount);
	
	private static native int sizeNM(long nmPointer);
	private static native long memoryUsedNM(long nmPointer);
	private static native long deleteNM(long nmPointer);
	
	private static boolean init = false;
	private static long totalAllocations;
	private static HashSet<Long> allocatedNativeMaps;
	
	private static synchronized long createNativeMap(){
		
		if(!init){
			allocatedNativeMaps = new HashSet<Long>();
			
			Runnable r = new Runnable(){
				@Override
				public void run() {
					if(allocatedNativeMaps.size() > 0){
						//print to system err in case log4j is shutdown... 
						try {
						    log.warn("There are "+allocatedNativeMaps.size()+" allocated native maps");
						} catch (Throwable t) {
						    log.error("There are "+allocatedNativeMaps.size()+" allocated native maps");
						}
					}
					
					log.debug(totalAllocations+" native maps were allocated");
				}
			};
			
			Runtime.getRuntime().addShutdownHook(new Thread(r));
			
			init = true;
		}
		
		long nmPtr = createNM();
		
		if(allocatedNativeMaps.contains(nmPtr)){
			//something is really screwy, this should not happen
			throw new RuntimeException(String.format("Duplicate native map pointer 0x%016x ", nmPtr));
		}
		
		totalAllocations++;
		allocatedNativeMaps.add(nmPtr);
		
		return nmPtr;
	}
	
	private static synchronized void deleteNativeMap(long nmPtr){
		if(allocatedNativeMaps.contains(nmPtr)){
			deleteNM(nmPtr);
			allocatedNativeMaps.remove(nmPtr);
		}else{
			throw new RuntimeException(String.format("Attempt to delete native map that is not allocated 0x%016x ", nmPtr));
		}
	}
	
	private static boolean loadedNativeLibraries = false;
	
	public static String getNativeLibPath(){
		return "lib/native/map/"+ System.mapLibraryName("NativeMap-" + Platform.getPlatform());
	}
	
	public static void loadNativeLib(String nativeLib) {
		try{
			System.load(nativeLib);
			log.info("Loaded native map shared library "+nativeLib);
			loadedNativeLibraries = true;
		}catch(Throwable t){
			log.error("Failed to load native map library "+nativeLib, t);
		}
	}
	
	static {
		String aHome = System.getenv("ACCUMULO_HOME");
		if(aHome != null){
			String nativeLib = aHome+"/"+getNativeLibPath();
			loadNativeLib(new File(nativeLib).getAbsolutePath());
		}
	}
	
	public static boolean loadedNativeLibraries(){
		return loadedNativeLibraries;
	}
	
	private static native long createNMI(long nmp, int fieldLens[]);
	private static native long createNMI(long nmp, byte[] row, byte cf[], byte cq[], byte cv[], long ts, boolean del, int fieldLens[]);
	
	private static native boolean nmiNext(long nmiPointer, int fieldLens[]);
	
	private static native void nmiGetData(long nmiPointer, byte[] row, byte cf[], byte cq[], byte cv[], byte[] valData);
	private static native long nmiGetTS(long nmiPointer);

	private static native void deleteNMI(long nmiPointer);
	
	private class ConcurrentIterator implements Iterator<Map.Entry<Key, Value>> {

		//in order to get good performance when there are multiple threads reading, need to read a lot while the
		//the read lock is held..... lots of threads trying to get the read lock very often causes serious slow
		//downs.... also reading a lot of entries at once lessens the impact of concurrent writes... if only
		//one entry were read at a time and there were concurrent writes, then iteration could be n*log(n)
		

		//increasing this number has a positive effect on concurrent read performance, but negatively effects 
		//concurrent writers
		private static final int MAX_READ_AHEAD_ENTRIES = 16;
		private static final int READ_AHEAD_BYTES = 4096;
		
		private NMIterator source;
		
		private Entry<Key, Value> nextEntries[];
		private int index;
		private int end;
		
		ConcurrentIterator(){
			this(new MemKey());
		}
		
		@SuppressWarnings("unchecked")
        ConcurrentIterator(Key key){
			//start off with a small read ahead
			nextEntries = new Entry[1];
			
			rlock.lock();
			try{
				source = new NMIterator(key);
				fill();
			}finally{
				rlock.unlock();
			}
		}

		//it is assumed the read lock is held when this method is called
		@SuppressWarnings("unchecked")
        private void fill() {
			end = 0;
			index = 0;
			
			if(source.hasNext())
				source.doNextPreCheck();
			
			int amountRead = 0;
			
			//as we keep filling, increase the read ahead buffer
			if(nextEntries.length < MAX_READ_AHEAD_ENTRIES)
				nextEntries = new Entry[Math.min(nextEntries.length * 2, MAX_READ_AHEAD_ENTRIES)];
			
			while(source.hasNext() && end < nextEntries.length){
				Entry<Key, Value> ne = source.next();
				nextEntries[end++] = ne;
				amountRead += ne.getKey().getSize() + ne.getValue().getSize();
				
				if(amountRead > READ_AHEAD_BYTES)
					break;
			}
		}
		
		@Override
		public boolean hasNext() {
			return end != 0;
		}

		@Override
		public Entry<Key, Value> next() {
			if(end == 0){
				throw new NoSuchElementException();
			}
			
			Entry<Key, Value> ret = nextEntries[index++];
			
			if(index == end){
				rlock.lock();
				try{
					fill();
				}catch(ConcurrentModificationException cme){
					source.delete();
					source = new NMIterator(ret.getKey());
					fill();
					if(0 < end && nextEntries[0].getKey().equals(ret.getKey())){
						index++;
						if(index == end){
							fill();
						}
					}
				}finally{
					rlock.unlock();
				}
				
			}

			return ret;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}

		public void delete() {
			source.delete();
		}
	}

	private class NMIterator implements Iterator<Map.Entry<Key, Value>> {
		
		/**
		 * The strategy for dealing with native memory allocated for iterators
		 * is to simply delete that memory when this Java Object is garbage 
		 * collected.
		 * 
		 * These iterators are likely short lived object and therefore will
		 * be quickly garbage collected.  Even if the objects are long lived
		 * and therefore more slowly garbage collected they only hold a small
		 * amount of native memory.
		 * 
		 */
		
		private long nmiPointer;
		private boolean hasNext;
		private int expectedModCount;
		private int[] fieldsLens = new int[7];
		private byte lastRow[];
		
		//it is assumed the read lock is held when this method is called
		NMIterator(Key key){

			if(nmPointer == 0){
				throw new IllegalStateException();
			}

			expectedModCount = modCount;

			nmiPointer = createNMI(nmPointer, key.getRowData().toArray(), 
					key.getColumnFamilyData().toArray(),
					key.getColumnQualifierData().toArray(),
					key.getColumnVisibilityData().toArray(), 
					key.getTimestamp(), 
					key.isDeleted(),
					fieldsLens);

			hasNext = nmiPointer != 0;
		}
		
		//delete is synchronized on a per iterator basis want to ensure only one 
		//thread deletes an iterator w/o acquiring the global write lock...
		//there is no contention among concurrent readers for deleting their iterators
		public synchronized void delete(){
			if(nmiPointer == 0){
				return;
			}

			//log.debug("Deleting native map iterator pointer");

			deleteNMI(nmiPointer);
			nmiPointer = 0;
		}
		
		
		
		@Override
		public boolean hasNext() {
			return hasNext;
		}

		//it is assumed the read lock is held when this method is called
		//this method only needs to be called once per read lock acquisition
		private void doNextPreCheck(){
			if(nmPointer == 0){
				throw new IllegalStateException();
			}

			if(modCount != expectedModCount){
				throw new ConcurrentModificationException();
			}
		}
		
		@Override
		//It is assumed that this method is called w/ the read lock held and
		//that doNextPreCheck() is called prior to calling this method
		//also this method is synchronized to ensure that a deleted iterator
		//is not used
		public synchronized Entry<Key, Value> next() {
			if(!hasNext){
				throw new NoSuchElementException();
			}

			if(nmiPointer == 0){
				throw new IllegalStateException("Native Map Iterator Deleted");
			}
			
			byte[] row = null;
			if(fieldsLens[0] >= 0){
				row = new byte[fieldsLens[0]];
				lastRow = row;
			}

			byte cf[] = new byte[fieldsLens[1]];
			byte cq[] = new byte[fieldsLens[2]];
			byte cv[] = new byte[fieldsLens[3]];
			boolean deleted = fieldsLens[4] == 0 ? false : true;
			byte val[] = new byte[fieldsLens[5]];

			nmiGetData(nmiPointer, row, cf, cq, cv, val);
			long ts = nmiGetTS(nmiPointer);

			Key k = new MemKey(lastRow, cf, cq, cv, ts, deleted, false, fieldsLens[6]);		
			Value v = new Value(val, false);

			hasNext = nmiNext(nmiPointer, fieldsLens);

			return new NMEntry(k,v);
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
		
		@Override
		protected void finalize() throws Throwable {
			super.finalize();
			if(nmiPointer != 0){
				//log.debug("Deleting native map iterator pointer in finalize");
				deleteNMI(nmiPointer);
			}
		}
		
	}
	
	private static class NMEntry implements Map.Entry<Key, Value>{

		private Key key;
		private Value val;
		
		NMEntry(Key k, Value v){
			this.key = k;
			this.val = v;
		}
		
		@Override
		public Key getKey() {
			return key;
		}

		@Override
		public Value getValue() {
			return val;
		}

		@Override
		public Value setValue(Value value) {
			throw new UnsupportedOperationException();
		}
		
		public String toString(){
			return key+"="+val;
		}
	}
	
	public NativeMap(){
		nmPointer = createNativeMap();
		rwLock = new ReentrantReadWriteLock();
		rlock = rwLock.readLock();
		wlock = rwLock.writeLock();
		log.debug(String.format("Allocated native map 0x%016x", nmPointer));
	}
	
	@Override
	protected void finalize() throws Throwable {
		super.finalize();
		if(nmPointer != 0){
			log.warn(String.format("Deallocating native map 0x%016x in finalize", nmPointer));
			deleteNativeMap(nmPointer);
		}
	}
	
	private void _mutate(Mutation mutation, int mutationCount) {
		

		List<ColumnUpdate> updates = mutation.getUpdates();
		if(updates.size() == 1){
			ColumnUpdate update = updates.get(0);
			singleUpdate(nmPointer, mutation.getRow(), update.getColumnFamily(), update.getColumnQualifier(), update.getColumnVisibility(), update.getTimestamp(), update.isDeleted(), update.getValue(), mutationCount);
		}else if(updates.size() > 1){
			long uid = startUpdate(nmPointer, mutation.getRow());
			for (ColumnUpdate update : updates) {
				update(nmPointer, uid, update.getColumnFamily(), update.getColumnQualifier(), update.getColumnVisibility(), update.getTimestamp(), update.isDeleted(), update.getValue(), mutationCount);
			}

		}
	}
	
	public void mutate(Mutation mutation, int mutationCount){
		wlock.lock();
		try{
			if(nmPointer == 0){
				throw new IllegalStateException("Native Map Deleted");
			}

			modCount++;
			
			_mutate(mutation, mutationCount);
		}finally{
			wlock.unlock();
		}
	}
	
	public void mutate(List<Mutation> mutations, int mutationCount) {
		Iterator<Mutation> iter = mutations.iterator();
		
		while(iter.hasNext()){
			
			wlock.lock();
			try{
				if(nmPointer == 0){
					throw new IllegalStateException("Native Map Deleted");
				}

				modCount++;
				
				int count = 0;
				while(iter.hasNext() && count < 10){
					Mutation mutation = iter.next();
					_mutate(mutation, mutationCount);
					mutationCount++;
					count += mutation.size();
				}
			}finally{
				wlock.unlock();
			}	
		}
	}
	
	public void put(Key key, Value value) {
		wlock.lock();
		try{
			if(nmPointer == 0){
				throw new IllegalStateException("Native Map Deleted");
			}

			modCount++;

			singleUpdate(nmPointer, key.getRowData().toArray(), 
					key.getColumnFamilyData().toArray(),
					key.getColumnQualifierData().toArray(),
					key.getColumnVisibilityData().toArray(),
					key.getTimestamp(),
					key.isDeleted(),
					value.get(),
					0);
		}finally{
			wlock.unlock();
		}
	}
	
	public Value get(Key key){
		rlock.lock();
		try{
			Value ret = null;
			NMIterator nmi = new NMIterator(key);
			if(nmi.hasNext()){
				Entry<Key, Value> entry = nmi.next();
				if(entry.getKey().equals(key)){
					ret = entry.getValue();
				}
			}

			nmi.delete();
			
			return ret;
		}finally{
			rlock.unlock();
		}
	}
	
	public int size(){
		rlock.lock();
		try{
			if(nmPointer == 0){
				throw new IllegalStateException("Native Map Deleted");
			}

			return sizeNM(nmPointer);
		}finally{
			rlock.unlock();
		}
	}
	
	public long getMemoryUsed(){
		rlock.lock();
		try{
			if(nmPointer == 0){
				throw new IllegalStateException("Native Map Deleted");
			}
			
			return memoryUsedNM(nmPointer);
		}finally{
			rlock.unlock();
		}
	}
	
	public Iterator<Map.Entry<Key, Value>> iterator(){
		rlock.lock();
		try{
			if(nmPointer == 0){
				throw new IllegalStateException("Native Map Deleted");
			}
			
			return new ConcurrentIterator();
		}finally{
			rlock.unlock();
		}
	}
	
	public Iterator<Map.Entry<Key, Value>> iterator(Key startKey){
		rlock.lock();
		try{

			if(nmPointer == 0){
				throw new IllegalStateException("Native Map Deleted");
			}

			return new ConcurrentIterator(startKey);
		}finally{
			rlock.unlock();
		}
	}
	
	public void delete(){
		wlock.lock();
		try{
			if(nmPointer == 0){
				throw new IllegalStateException("Native Map Deleted");
			}

			log.debug(String.format("Deallocating native map 0x%016x", nmPointer));
			deleteNativeMap(nmPointer);
			nmPointer = 0;
		}finally{
			wlock.unlock();
		}
	}
	
	private static class NMSKVIter implements InterruptibleIterator {

		private ConcurrentIterator iter;
		private Entry<Key, Value> entry;

		private NativeMap map;
		private Range range;
		private AtomicBoolean interruptFlag;
		private int interruptCheckCount = 0;
		
		private NMSKVIter(NativeMap map, AtomicBoolean interruptFlag){
			this.map = map;
			this.range = new Range();
			iter = map.new ConcurrentIterator();
			if(iter.hasNext())
				entry = iter.next();
			else
				entry = null;
			
			this.interruptFlag = interruptFlag;
		}
		
		public NMSKVIter(NativeMap map){
			this(map, null);
		}

		@Override
		public Key getTopKey() {
			return entry.getKey();
		}

		@Override
		public Value getTopValue() {
			return entry.getValue();
		}

		@Override
		public boolean hasTop() {
			return entry != null;
		}

		@Override
		public void next() throws IOException {
			
			if(entry == null) throw new IllegalStateException();
			
			//checking the interrupt flag for every call to next had bad a bad performance impact
			//so check it every 100th time
			if(interruptFlag != null && interruptCheckCount++ % 100 == 0 && interruptFlag.get())
				throw new IterationInterruptedException();
	
			if(iter.hasNext()){
				entry = iter.next();
				if(range.afterEndKey(entry.getKey())){
					entry = null;
				}
			}else
				entry = null;
			
		}

		@Override
		public void seek(Range range, Collection<ByteSequence> columnFamilies, boolean inclusive) throws IOException {
			
			if(columnFamilies.size() != 0 || inclusive){
				throw new IllegalArgumentException("I do not know how to filter column families");
			}
			
			if(interruptFlag != null && interruptFlag.get())
				throw new IterationInterruptedException();
			
			iter.delete();
			
			this.range = range;
			
			Key key = range.getStartKey();
			if(key == null){
				key = new MemKey();
			}
			
			iter = map.new ConcurrentIterator(key);
			if(iter.hasNext()){
				entry = iter.next();
				if(range.afterEndKey(entry.getKey())){
					entry = null;
				}
			}else
				entry = null;
			
			while(hasTop() && range.beforeStartKey(getTopKey())){
				next();
			}
		}

		@Override
		public void init(SortedKeyValueIterator<Key, Value> source,
				Map<String, String> options, IteratorEnvironment env) throws IOException {
			throw new UnsupportedOperationException();
		}

		@Override
		public SortedKeyValueIterator<Key, Value> deepCopy(IteratorEnvironment env) {
			return new NMSKVIter(map, interruptFlag);
		}

		@Override
		public void setInterruptFlag(AtomicBoolean flag) {
			this.interruptFlag = flag;
		}
	}
	
	public SortedKeyValueIterator<Key, Value> skvIterator() {
		return new NMSKVIter(this);
	}
}
