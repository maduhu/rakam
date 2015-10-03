package org.rakam;

import com.google.inject.Singleton;
import org.apache.commons.dbcp2.BasicDataSource;
import org.rakam.analysis.postgresql.PostgresqlConfig;

import javax.inject.Inject;

import java.sql.Connection;
import java.sql.SQLException;

@Singleton
public class PostgresqlPoolDataSource {

    private final BasicDataSource connectionPool;

    @Inject
    public PostgresqlPoolDataSource(PostgresqlConfig config) {
        connectionPool = new BasicDataSource();
        connectionPool.setUsername(config.getUsername());
        connectionPool.setPassword(config.getPassword());
        connectionPool.setDriverClassName(org.postgresql.Driver.class.getName());
        connectionPool.setUrl("jdbc:postgresql://" + config.getHost() + ':' + config.getPort() + "/" + config.getDatabase());
        connectionPool.setMaxTotal(config.getMaxConnection());
        connectionPool.setPoolPreparedStatements(true);
    }

    public Connection getConnection() throws SQLException {
        return connectionPool.getConnection();
    }
}