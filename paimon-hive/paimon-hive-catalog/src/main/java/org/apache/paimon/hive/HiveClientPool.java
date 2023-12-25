/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.paimon.hive;

import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.hive.pool.ClientPoolImpl;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;

/**
 * HiveClientPool.
 *
 * <p>Mostly copied from iceberg.
 */
public class HiveClientPool extends ClientPoolImpl<IMetaStoreClient, TException> {

    private final HiveConf hiveConf;

    private final String clientClassName;

    public HiveClientPool(int poolSize, HiveConf conf) {
        this(poolSize, conf, "org.apache.hadoop.hive.metastore.HiveMetaStoreClient");
    }

    public HiveClientPool(int poolSize, HiveConf conf, String clientClassName) {
        // Do not allow retry by default as we rely on RetryingHiveClient
        super(poolSize, TTransportException.class, false);
        this.hiveConf = conf;
        this.clientClassName = clientClassName;
    }

    @Override
    protected IMetaStoreClient newClient() {
        try {
            try {
                return new RetryingMetaStoreClientFactory().createClient(hiveConf, clientClassName);
            } catch (RuntimeException e) {
                // any MetaException would be wrapped into RuntimeException during reflection, so
                // let's double-check type here
                if (e.getCause() instanceof MetaException) {
                    throw (MetaException) e.getCause();
                }
                throw e;
            }
        } catch (MetaException e) {
            throw new RuntimeException("Failed to connect to Hive Metastore", e);
        } catch (Throwable t) {
            if (t.getMessage().contains("Another instance of Derby may have already booted")) {
                throw new RuntimeException(
                        "Failed to start an embedded metastore because embedded "
                                + "Derby supports only one client at a time. To fix this, use a metastore that supports "
                                + "multiple clients.",
                        t);
            }

            throw new RuntimeException("Failed to connect to Hive Metastore", t);
        }
    }

    @Override
    protected IMetaStoreClient reconnect(IMetaStoreClient client) {
        try {
            client.close();
            client.reconnect();
        } catch (MetaException e) {
            throw new RuntimeException("Failed to reconnect to Hive Metastore", e);
        }
        return client;
    }

    @Override
    protected boolean isConnectionException(Exception e) {
        return super.isConnectionException(e)
                || (e instanceof MetaException
                        && e.getMessage()
                                .contains(
                                        "Got exception: org.apache.thrift.transport.TTransportException"));
    }

    @Override
    protected void close(IMetaStoreClient client) {
        client.close();
    }

    @VisibleForTesting
    HiveConf hiveConf() {
        return hiveConf;
    }
}
