package com.nijiko.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.sqlite.SQLiteDataSource;

import com.mysql.jdbc.jdbc2.optional.MysqlDataSource;
import com.nijikokun.bukkit.Permissions.Permissions;

public abstract class SqlStorage {
    private static DataSource dbSource;
    private static int reloadId;
    private static boolean init = false;
    private static Map<String, SqlUserStorage> userStores = new HashMap<String, SqlUserStorage>();
    private static Map<String, SqlGroupStorage> groupStores = new HashMap<String, SqlGroupStorage>();
    private static Map<String, Integer> worldMap = new HashMap<String, Integer>();
    private static List<String> create = new ArrayList<String>(8);

    static final String getWorld = "SELECT Worlds.worldid FROM Worlds WHERE Worlds.worldname = '?';";
    static final String getUser = "SELECT userid FROM Users WHERE Users.worldid = ? AND Users.username = '?';";
    static final String getGroup = "SELECT * FROM Groups WHERE Groups.worldid = ? AND Groups.groupname = '?';";

    static final String createWorld = "INSERT INTO Worlds (worldname) VALUES ('?');";
    static final String createUser = "INSERT INTO Users (worldid,username) VALUES (?,'?');";
    static final String createGroup = "INSERT INTO Groups (worldid, groupname, prefix, suffix) VALUES (?,'?', '','', 0,0);";

    static {
        create.add("CREATE TABLE IF NOT EXISTS Worlds (" + " worldid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " worldname VARCHAR(32) NOT NULL UNIQUE" + ");");
        create.add("CREATE TABLE IF NOT EXISTS Users (" + " uid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " username VARCHAR(32) NOT NULL," + " worldid INTEGER NOT NULL," + " CONSTRAINT UserNameWorld UNIQUE (username, worldid)," + " FOREIGN KEY(worldid) REFERENCES Worlds(worldid)" + ");");
        create.add("CREATE TABLE IF NOT EXISTS Groups (" + " gid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " groupname VARCHAR(32) NOT NULL," + " worldid  INTEGER NOT NULL," + " prefix VARCHAR(32) NOT NULL," + " suffix VARCHAR(32) NOT NULL, " + " build TINYINT NOT NULL DEFAULT 0," + " weight INTEGER NOT NULL DEFAULT 0," + " CONSTRAINT GroupNameWorld UNIQUE (groupname, worldid)," + " FOREIGN KEY(worldid) REFERENCES Worlds(worldid)" + ");");
        create.add("CREATE TABLE IF NOT EXISTS UserPermissions (" + " upermid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " permstring VARCHAR(64) NOT NULL," + " uid INTEGER NOT NULL," + " CONSTRAINT UserPerm UNIQUE (uid, permstring)," + " FOREIGN KEY(uid) REFERENCES Users(uid)" + ");");
        create.add("CREATE TABLE IF NOT EXISTS GroupPermissions (" + " gpermid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " permstring VARCHAR(64) NOT NULL," + " gid INTEGER NOT NULL," + " CONSTRAINT GroupPerm UNIQUE (gid, permstring)," + " FOREIGN KEY(gid) REFERENCES Groups(gid)" + ");");
        create.add("CREATE TABLE IF NOT EXISTS UserInheritance (" + " uinheritid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " childid INTEGER NOT NULL," + " parentid INTEGER NOT NULL," + " CONSTRAINT UserParent UNIQUE (childid, parentid)," + " FOREIGN KEY(childid) REFERENCES Users(uid)," + " FOREIGN KEY(parentid) REFERENCES Groups(gid)" + ");");
        create.add("CREATE TABLE IF NOT EXISTS GroupInheritance (" + " ginheritid INTEGER NOT NULL PRIMARY KEY," + " childid INTEGER NOT NULL," + " parentid INTEGER NOT NULL," + " CONSTRAINT UserParent UNIQUE (childid, parentid)," + " CONSTRAINT GroupNoSelfInherit CHECK (childid <> parentid)," + " FOREIGN KEY(childid) REFERENCES Groups(gid)," + " FOREIGN KEY(parentid) REFERENCES Groups(gid)" + ");");
        create.add("CREATE TABLE IF NOT EXISTS WorldBase (" + " worldid INTEGER NOT NULL," + " defaultid INTEGER," + "FOREIGN KEY(worldid) REFERENCES Worlds(worldid)," + "FOREIGN KEY(defaultid) REFERENCES Groups(gid)" + ");");
        create.add("CREATE TABLE IF NOT EXISTS UserData (" + " dataid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " uid INTEGER NOT NULL ," + " path VARCHAR(64) NOT NULL," + " data VARCHAR(64) NOT NULL," + " CONSTRAINT UserDataUnique UNIQUE (uid, path)," + "FOREIGN KEY(uid) REFERENCES Users(uid)" + ");");
        create.add("CREATE TABLE IF NOT EXISTS GroupData (" + " dataid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " gid INTEGER NOT NULL," + " path VARCHAR(64) NOT NULL," + " data VARCHAR(64) NOT NULL," + " CONSTRAINT GroupDataUnique UNIQUE (gid, path)," + "FOREIGN KEY(gid) REFERENCES Groups(gid)" + ");");
        create.add("CREATE TABLE IF NOT EXISTS Tracks (" + " trackid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " trackname VARCHAR(64) NOT NULL UNIQUE," + "worldid INTEGER NOT NULL,"+ "FOREIGN KEY(worldid) REFERENCES Worlds(worldid)"+");");
        create.add("CREATE TABLE IF NOT EXISTS TrackGroups (" + " trackgroupid INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," + " trackid INTEGER NOT NULL," + " gid INTEGER NOT NULL," + " groupOrder INTEGER NOT NULL,"+" CONSTRAINT TrackGroupsUnique UNIQUE (trackid, gid)," + "FOREIGN KEY(trackid) REFERENCES Tracks(trackid)," + "FOREIGN KEY(gid) REFERENCES Groups(gid)" + ");");
    }

    public static void init(String dbmsName, String uri, String username, String password, int reloadDelay) throws Exception {
        if (init)
            return;
        // SqlStorage.reloadDelay = reloadDelay;
        Dbms dbms = null;
        try {
            dbms = Dbms.valueOf(dbmsName);
        } catch (IllegalArgumentException e) {
            System.err.println("Error occurred while selecting permissions config DBMS. Reverting to SQLite.");
            dbms = Dbms.SQLITE;
        }
        try {
            Class.forName(dbms.getDriver());
        } catch (ClassNotFoundException e) {
            throw new Exception("Unable to load SQL driver!", e);
        }
        dbSource = dbms.getSource(username, password, uri);
        verifyAndCreateTables(dbms);
        reloadId = Permissions.instance.getServer().getScheduler().scheduleAsyncRepeatingTask(Permissions.instance, new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, reloadDelay, reloadDelay);
        init = true;
        refresh();
    }

    private synchronized static void refresh() // Used for periodic cache flush
    {
        worldMap.clear();
        for (SqlUserStorage instance : userStores.values()) {
            instance.reload();
        }
        for (SqlGroupStorage instance : groupStores.values()) {
            instance.reload();
        }
    }

    private static void verifyAndCreateTables(Dbms dbms) throws SQLException {
        Connection dbConn = SqlStorage.dbSource.getConnection();
        Statement s = dbConn.createStatement();
        // Verify stuff
        String engine = dbms.equals(Dbms.MYSQL) ? " ENGINE = InnoDB;" : ";";
        for (String state : create) {
            if(dbms==Dbms.MYSQL) {
                state = state.replace("AUTOINCREMENT", "AUTO_INCREMENT");
            }
            s.executeUpdate(state + engine);
        }
        s.executeUpdate("CREATE INDEX IF NOT EXISTS username_index ON Users(username);");
    }

    static DataSource getSource() {
        return dbSource;
    }

    static int getWorld(String name) throws SQLException {
        if (worldMap.containsKey(name))
            return worldMap.get(name);
        Connection dbConn = getConnection();
        Statement stmt = dbConn.createStatement();
        String query = getWorld.replace("?", name);
        ResultSet rs = stmt.executeQuery(query);
        if (!rs.next()) {
            System.out.println("[Permissions] Creating world '" + name + "'.");
            String addQuery = createWorld.replace("?", name);
            stmt.executeUpdate(addQuery);
            rs = stmt.executeQuery(query);
            rs.next();
        }
        int id = rs.getInt(1);
        worldMap.put(name, id);
        rs.close();
        stmt.close();
        dbConn.close();
        return id;

    }

    static int getUser(String world, String name) throws SQLException {
        SqlUserStorage sus = userStores.get(world);
        if (sus != null) {
            Integer id = sus.getUserId(name);
            if (id != null)
                return id;
        }
        Connection dbConn = getConnection();
        Statement stmt = dbConn.createStatement();
        int worldId = getWorld(world);
        String query = getUser.replaceFirst("\\?", String.valueOf(worldId)).replaceFirst("\\?", name);
        ResultSet rs = stmt.executeQuery(query);
        if (!rs.next()) {
            System.out.println("[Permissions] Creating user '" + name + "' in world '" + world + "'.");
            String addQuery = createUser.replaceFirst("\\?", String.valueOf(worldId)).replaceFirst("\\?", name);
            stmt.executeUpdate(addQuery);
            rs = stmt.executeQuery(query);
            rs.next();
        }
        int id = rs.getInt(1);
        rs.close();
        stmt.close();
        dbConn.close();
        return id;

    }

    static int getGroup(String world, String name) throws SQLException {
        SqlGroupStorage sgs = groupStores.get(world);
        if (sgs != null) {
            Integer id = sgs.getGroupId(name);
            if (id != null)
                return id;
        }
        Connection dbConn = getConnection();
        Statement stmt = dbConn.createStatement();
        int worldId = getWorld(world);
        String query = getGroup.replaceFirst("\\?", String.valueOf(worldId)).replaceFirst("\\?", name);
        ResultSet rs = stmt.executeQuery(query);
        if (!rs.next()) {
            System.out.println("[Permissions] Creating group '" + name + "' in world '" + world + "'.");
            String addQuery = createGroup.replaceFirst("\\?", String.valueOf(worldId)).replaceFirst("\\?", name);
            stmt.executeUpdate(addQuery);
            rs = stmt.executeQuery(query);
            rs.next();
        }
        int id = rs.getInt(1);
        rs.close();
        stmt.close();
        dbConn.close();
        return id;
    }

    static SqlUserStorage getUserStorage(String world) throws SQLException {
        if (userStores.containsKey(world))
            return userStores.get(world);
        SqlUserStorage sus = new SqlUserStorage(world, getWorld(world));
        userStores.put(sus.getWorld(), sus);
        return sus;
    }

    static SqlGroupStorage getGroupStorage(String world) throws SQLException {
        if (groupStores.containsKey(world))
            return groupStores.get(world);
        SqlGroupStorage sgs = new SqlGroupStorage(world, getWorld(world));
        groupStores.put(sgs.getWorld(), sgs);
        return sgs;
    }

    synchronized static void closeAll() {
        try {
            for (SqlUserStorage sus : userStores.values()) {
                sus.close();
            }
            Permissions.instance.getServer().getScheduler().cancelTask(reloadId);
            init = false;
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    static Connection getConnection() throws SQLException {
        return dbSource.getConnection();
    }
}

enum Dbms {
    SQLITE("org.sqlite.JDBC"), MYSQL("com.mysql.jdbc.driver");
    private final String driver;

    Dbms(String driverClass) {
        this.driver = driverClass;
    }

    public String getDriver() {
        return driver;
    }

    public DataSource getSource(String username, String password, String url) {
        switch (this) {
        case MYSQL:
            MysqlDataSource mds = new MysqlDataSource();
            mds.setUser(username);
            mds.setPassword(password);
            mds.setUrl(url);
            return mds;
        default:
        case SQLITE:
            SQLiteDataSource sds = new SQLiteDataSource();
            sds.setUrl(url);
            return sds;
        }
    }
}