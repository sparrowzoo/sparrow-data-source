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

import com.sparrow.concurrent.SparrowThreadFactory;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mysql数据库链接池
 *
 * @author harry
 * @version 1.0
 */
public class ConnectionPool implements DataSource {
    private static Logger logger = LoggerFactory.getLogger(ConnectionPool.class);
    
    private int openedConnectionCount = 0;
    private int closedConnectionCount = 0;

    private PrintWriter logWriter;
    private int loginTimeout = 0;
    /**
     * 数据池数组对象 <p> connectionPool加最后一次使用时间
     */
    public List<Connection> pool;

    public Map<Connection, Long> usedPool;

    private String dataSourceKey;

    private DatasourceConfig connectionConfig;

    /**
     * 单子构造
     */
    public ConnectionPool() {
        init();
    }

    /**
     * 唯一确定一个数据源
     *
     * @param dataSourceKey 数据源
     */
    public ConnectionPool(String dataSourceKey) {
        this.dataSourceKey = dataSourceKey;
        this.init();
    }

    /**
     * 初始化数据库链接池
     */
    private void init() {
        this.connectionConfig = DataSourceFactory.getInstance().getDatasourceConfig(this.dataSourceKey);
        pool = new Vector<Connection>(this.connectionConfig.getPoolSize());
        this.usedPool = new ConcurrentHashMap<Connection, Long>(this.connectionConfig.getPoolSize());
        for (int i = 0; i < this.connectionConfig.getPoolSize(); i++) {
            pool.add(this.newConnection());
        }

        ScheduledExecutorService scheduledExecutorService = new ScheduledThreadPoolExecutor(1,
            new SparrowThreadFactory.Builder().namingPattern("datasource-monitor-%d").daemon(true).build());

        scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                Map<Connection, Long> usedPool = ConnectionPool.this.usedPool;
                List<Connection> pool = ConnectionPool.this.pool;
                for (Connection c : usedPool.keySet()) {
                    if ((System.currentTimeMillis() - usedPool.get(c)) / 1000 / 60d > ConnectionPool.this
                        .getLoginTimeout()) {
                        usedPool.remove(c);
                        pool.add(c);
                    }
                }
            }
        }, 5, 1, TimeUnit.SECONDS);
    }

    /**
     * 关闭连接池中的所有数据库连接
     */
    public synchronized void closePool() {
        for (int i = 0; i < pool.size(); i++) {
            try {
                (pool.get(i)).close();
            } catch (SQLException e) {
                logger.error("close pool error", e);
            }
            pool.remove(i);
        }
    }

    /**
     * 从链接池中获取链接对象
     *
     * @return Connection
     */
    @Override
    public synchronized Connection getConnection() {
        while (true) {
            Connection conn;
            if (pool.size() > 0) {
                // 如果池中存在链接对象则从池获取
                conn = pool.remove(0);
                try {
                    if (conn == null || conn.isClosed()) {
                        // 被物理关掉了，或其他原因丢掉链接
                        this.closedConnectionCount++;
                    }
                } catch (SQLException e) {
                    logger.error("connection error", e);
                }
            } else {
                // 如果池中不存在
                conn = newConnection();
            }
            // 从池中获取默认为自动提交
            try {
                if (conn != null) {
                    conn.setAutoCommit(true);
                    this.usedPool.put(conn, System.currentTimeMillis());
                    return conn;
                }
            } catch (SQLException e) {
                logger.error("set auto commit error", e);
            }
        }
    }

    private Connection newConnection() {
        Connection conn = null;
        // 此处不要放入池中.release时即放回池中
        try {
            conn = new ProxyConnection(this.connectionConfig, this);
            // 打开过的所有新链接
            this.openedConnectionCount++;
        } catch (Exception e) {
            logger.error("new connection error", e);
        }
        return conn;
    }

    @Override
    public synchronized PrintWriter getLogWriter() throws SQLException {
        return this.logWriter;
    }

    @Override
    public int getLoginTimeout() {
        return this.loginTimeout;
    }

    @Override
    public void setLogWriter(PrintWriter arg0) throws SQLException {
        this.logWriter = arg0;
    }

    @Override
    public void setLoginTimeout(int arg0) {
        this.loginTimeout = arg0;
    }

    @Override
    public boolean isWrapperFor(Class<?> arg0) throws SQLException {
        return DataSource.class.equals(arg0);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> arg0) throws SQLException {
        if (this.isWrapperFor(arg0)) {
            return (T) arg0;
        } else {
            throw new SQLException(
                "no object found that implements the interface");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.connectionConfig);
        sb.append(" information list");
        sb.append("<br/>");
        sb.append(" opened connection count:");
        sb.append(this.openedConnectionCount);
        sb.append("<br/>");
        sb.append("physical closed connection count:");
        sb.append(this.closedConnectionCount);
        sb.append("<br/>");
        sb.append("pool connection count:");
        sb.append(this.pool.size());
        sb.append("<br/>");
        sb.append("used pool connection count:");
        sb.append(this.usedPool.size());
        return sb.toString();
    }

    @Override
    public Connection getConnection(String username, String password)
        throws SQLException {
        throw new SQLException(
            "not support getConnection(String username, String password) method");
    }

    //jdk1.6 without this api
    public java.util.logging.Logger getParentLogger()
        throws SQLFeatureNotSupportedException {
        return null;
    }
}
