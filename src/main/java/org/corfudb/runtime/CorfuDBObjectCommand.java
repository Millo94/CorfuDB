/**
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.corfudb.runtime;

import java.io.Serializable;

public class CorfuDBObjectCommand implements Serializable
{
    Exception E=null;
    Object retval=null;
    long txid = -1;
    public Object getReturnValue()
    {
        return retval;
    }
    public void setReturnValue(Object obj)
    {
        retval = obj;
    }
    public long getTxid() { return txid; }
    public void setTxid(long l) { txid = l; }
    public void setException(Exception tE)
    {
        E = tE;
    }
    public Exception getException()
    {
        return E;
    }
}
