package org.rakam.postgresql.analysis;

import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import org.rakam.analysis.ApiKeyService;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.util.CryptUtil;

import javax.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class PostgresqlApiKeyService implements ApiKeyService {
    private final LoadingCache<String, List<Set<String>>> apiKeyCache;
    private final JDBCPoolDataSource connectionPool;

    public PostgresqlApiKeyService(JDBCPoolDataSource connectionPool) {
        this.connectionPool = connectionPool;

        apiKeyCache = CacheBuilder.newBuilder().build(new CacheLoader<String, List<Set<String>>>() {
            @Override
            public List<Set<String>> load(String project) throws Exception {
                try (Connection conn = connectionPool.getConnection()) {
                    return getKeys(conn, project);
                }
            }
        });
    }

    @PostConstruct
    public void setup() {
        try (Connection connection = connectionPool.getConnection()) {
            Statement statement = connection.createStatement();
            statement.execute("CREATE TABLE IF NOT EXISTS api_key (" +
                    "  id SERIAL PRIMARY KEY,\n" +
                    "  project TEXT NOT NULL,\n" +
                    "  read_key TEXT NOT NULL,\n" +
                    "  write_key TEXT NOT NULL,\n" +
                    "  master_key TEXT NOT NULL,\n" +
                    "  created_at TIMESTAMP default current_timestamp NOT NULL\n" +
                    "  )");
        } catch (SQLException e) {
            Throwables.propagate(e);
        }
    }

    @Override
    public ProjectApiKeys createApiKeys(String project) {

        String masterKey = CryptUtil.generateRandomKey(64);
        String readKey = CryptUtil.generateRandomKey(64);
        String writeKey = CryptUtil.generateRandomKey(64);

        int id;
        try (Connection connection = connectionPool.getConnection()) {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO public.api_key " +
                            "(master_key, read_key, write_key, project) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, masterKey);
            ps.setString(2, readKey);
            ps.setString(3, writeKey);
            ps.setString(4, project);
            ps.executeUpdate();
            final ResultSet generatedKeys = ps.getGeneratedKeys();
            generatedKeys.next();
            id = generatedKeys.getInt(1);
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }

        return new ProjectApiKeys(id, project, masterKey, readKey, writeKey);
    }

    @Override
    public void revokeApiKeys(String project, int id) {
        try(Connection conn = connectionPool.getConnection()) {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM public.api_key WHERE project = ? AND id = ?");
            ps.setString(1, project);
            ps.setInt(2, id);
            ps.execute();
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public boolean checkPermission(String project, AccessKeyType type, String apiKey) {
        try {
            boolean exists = apiKeyCache.get(project).get(type.ordinal()).contains(apiKey);
            if (!exists) {
                apiKeyCache.refresh(project);
                return apiKeyCache.get(project).get(type.ordinal()).contains(apiKey);
            }
            return true;
        } catch (ExecutionException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public List<ProjectApiKeys> getApiKeys(int[] ids) {
        try (Connection conn = connectionPool.getConnection()) {
            final PreparedStatement ps = conn.prepareStatement("select id, project, master_key, read_key, write_key from api_key where id = any(?)");
            ps.setArray(1, conn.createArrayOf("integer", Arrays.stream(ids).mapToObj(i -> i).toArray()));
            ps.execute();
            final ResultSet resultSet = ps.getResultSet();
            final List<ProjectApiKeys> list = Lists.newArrayList();
            while (resultSet.next()) {
                list.add(new ProjectApiKeys(resultSet.getInt(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5)));
            }
            return Collections.unmodifiableList(list);
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void revokeAllKeys(String project) {
        try(Connection conn = connectionPool.getConnection()) {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM public.api_key WHERE project = ?");
            ps.setString(1, project);
            ps.execute();
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    private List<Set<String>> getKeys(Connection conn, String project) throws SQLException {
        Set<String> masterKeyList = new HashSet<>();
        Set<String> readKeyList = new HashSet<>();
        Set<String> writeKeyList = new HashSet<>();

        Set<String>[] keys =
                Arrays.stream(AccessKeyType.values()).map(key -> new HashSet<String>()).toArray(Set[]::new);

        PreparedStatement ps = conn.prepareStatement("SELECT master_key, read_key, write_key from api_key WHERE project = ?");
        ps.setString(1, project);
        ResultSet resultSet = ps.executeQuery();
        while (resultSet.next()) {
            String apiKey;

            apiKey = resultSet.getString(1);
            if (apiKey != null) {
                masterKeyList.add(apiKey);
            }
            apiKey = resultSet.getString(2);
            if (apiKey != null) {
                readKeyList.add(apiKey);
            }
            apiKey = resultSet.getString(3);
            if (apiKey != null) {
                writeKeyList.add(apiKey);
            }
        }

        keys[AccessKeyType.MASTER_KEY.ordinal()] = Collections.unmodifiableSet(masterKeyList);
        keys[AccessKeyType.READ_KEY.ordinal()] = Collections.unmodifiableSet(readKeyList);
        keys[AccessKeyType.WRITE_KEY.ordinal()] = Collections.unmodifiableSet(writeKeyList);

        return Collections.unmodifiableList(Arrays.asList(keys));
    }

    public void clearCache() {
        apiKeyCache.cleanUp();
    }
}
