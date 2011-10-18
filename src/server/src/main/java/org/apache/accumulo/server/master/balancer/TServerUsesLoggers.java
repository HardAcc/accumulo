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
package org.apache.accumulo.server.master.balancer;

import java.util.Collections;
import java.util.Set;

import org.apache.accumulo.core.master.thrift.TabletServerStatus;
import org.apache.accumulo.server.master.state.TServerInstance;


public class TServerUsesLoggers implements LoggerUser {

	private TabletServerStatus status;
	private TServerInstance instance;
	
	public TServerUsesLoggers(TServerInstance instance, TabletServerStatus status) {
		this.instance = instance;
		this.status = status;
	}
	
	@Override
	public Set<String> getLoggers() {
		return Collections.unmodifiableSet(status.loggers);
	}

	@Override
	public int compareTo(LoggerUser o) {
	    if (o instanceof TServerUsesLoggers)
	        return instance.compareTo( ((TServerUsesLoggers)o).instance);
	    return -1;
	}

	@Override
	public int hashCode() {
		return instance.hashCode();
	}
	
	public TServerInstance getInstance() { return instance; }
}
