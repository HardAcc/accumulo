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
package org.apache.accumulo.core.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.apache.accumulo.core.util.StringUtil;
import org.junit.Test;

public class TestStringUtil {

    static List<String> parts(Object ... parts) {
        List<String> result = new ArrayList<String>();
        for (Object obj : parts) {
            result.add(obj.toString());
        }
        return result;
    }
    
    @Test
    public void testJoin() {
        assertEquals(StringUtil.join(parts(), ","), "");
        assertEquals(StringUtil.join(parts("a","b","c"), ","), "a,b,c");
        assertEquals(StringUtil.join(parts("a"), ","), "a");
        assertEquals(StringUtil.join(parts("a","a"), ","), "a,a");
    }

}
