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

package com.sparrow.datasource;

import com.sparrow.constant.SPARROW_ERROR;
import com.sparrow.transaction.Transaction;
import com.sparrow.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 在start 之前要手动将suffix设置
 *
 * @author harry
 */
public class SparrowTransactionManager implements com.sparrow.transaction.TransactionManager {
    private static Logger logger = LoggerFactory.getLogger(SparrowTransactionManager.class);

    private ConnectionContextHolder connectionHolder;

    private DataSourceFactory dataSourceFactory;

    public void setConnectionHolder(ConnectionContextHolder connectionHolder) {
        this.connectionHolder = connectionHolder;
    }

    public void setDataSourceFactory(DataSourceFactory dataSourceFactory) {
        this.dataSourceFactory = dataSourceFactory;
    }

    @Override
    public <T> T start(Transaction<T> transaction) throws BusinessException {
        return this.start(transaction, null);
    }

    @Override
    public <T> T start(Transaction<T> transaction, String dataSourceKey) throws BusinessException {
        Connection connection = null;
        try {
            DataSource dataSource = this.dataSourceFactory.getDataSource(dataSourceKey);
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            connection.setReadOnly(false);
            //绑定到事务的线程上
            this.connectionHolder.bindConnection(connection);
            T result = transaction.execute();
            connection.commit();
            return result;
        } catch (Exception e) {
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException ignore) {
                }
            }
            logger.error("db transection error", e);
            throw new BusinessException(SPARROW_ERROR.SYSTEM_SERVER_ERROR);
        } finally {
            this.connectionHolder.unbindConnection(connection);
        }
    }
}
