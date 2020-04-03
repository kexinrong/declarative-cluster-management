package org.dcm.viewupdater;

import ddlogapi.DDlogAPI;
import ddlogapi.DDlogCommand;
import ddlogapi.DDlogRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ViewUpdater can be used to help update views held in the DB incrementally, using DDLog. The class receives triggers
 * based on updates on base tables in the DB. These updates are held in memory until the user calls the function
 * "flushUpdates". Then, these updates are passed to DDlog, that incrementally computes views on them and returns
 * updates. Finally, we push these updates back to the DB.
 */
public abstract class ViewUpdater {
    String triggerClassName;
    final String key;

    private final Connection connection;
    private final List<String> baseTables;
    private final DSLContext dbCtx;
    private final Map<String, Map<DDlogCommand.Kind, PreparedStatement>> preparedQueries = new HashMap<>();
    private final DDlogUpdater updater;
    private final Map<String, Table<?>> tableMap;
    private final List<LocalDDlogCommand> recordsFromDDLog = new ArrayList<>();
    private final Map<String, Integer> recordsReceived = new HashMap<>();
    private final DDlogAPI api;

    private static final String BIGINT_TYPE = "java.math.BigInteger";
    private static final String INTEGER_TYPE = "java.lang.Integer";
    private static final String STRING_TYPE = "java.lang.String";
    private static final String BOOLEAN_TYPE = "java.lang.Boolean";
    private static final String LONG_TYPE = "java.lang.Long";

    // connection prefix (per model) -> <List of DDlogRecords per table, per model>
    // correct use requires that a new connection is used for every model
    static Map<String, List<LocalDDlogCommand>> mapRecordsFromDB = new ConcurrentHashMap<>();

    /**
     * @param connection: a connection to the DB used to build prepared statements
     * @param dbCtx: database context, mainly used to create triggers.
     * @param baseTables: the tables we build triggers for
     */
    public ViewUpdater(final Connection connection, final DSLContext dbCtx,
                       final List<String> baseTables, final DDlogAPI api) {
        this.connection = connection;
        // this key is connection-specific and allows us to separate records received from the DB for different models
        this.key = String.format("KEY%d", connection.hashCode());
        mapRecordsFromDB.computeIfAbsent(this.key, m -> new ArrayList<>());

        this.baseTables = baseTables;
        this.dbCtx = dbCtx;
        this.tableMap = new HashMap<>();
        // XXX: this is specific to H2
        final List<String> viewNames = dbCtx.fetch("select table_name from information_schema.views")
                                            .getValues("TABLE_NAME", String.class);
        for (final Table<?> table: dbCtx.meta().getTables()) {
            tableMap.put(table.getName().toUpperCase(Locale.US), table);

            if (viewNames.contains(table.getName())) {
                final String ddlForView = dbCtx.ddl(table).queries()[0].getSQL();
                try {
                    System.out.println(String.format("drop view %s cascade", table.getQualifiedName()));
                    dbCtx.execute(String.format("drop view %s cascade", table.getQualifiedName()));
                } catch (final DataAccessException e) {
                    System.out.println(e.getLocalizedMessage());
                }
                System.out.println(ddlForView);
                dbCtx.execute(ddlForView);
            }
        }
        this.api = api;
        this.updater = new DDlogUpdater(this::receiveUpdateFromDDlog, tableMap, api);
    }

    private String generatePreparedQueryString(final String dataType, final DDlogCommand.Kind commandKind) {
        final StringBuilder stringBuilder = new StringBuilder();
        final Table<? extends Record> table = tableMap.get(dataType);
        final Field<?>[] fields = table.fields();
        if (commandKind == DDlogCommand.Kind.Insert) {
            stringBuilder.append(String.format("insert into %s values ( %n", dataType));
            // for the first fields.length values, use a comma after the ?. No need to put a comma after the last ?
            stringBuilder.append(String.join(" ", "?,".repeat(Math.max(0, fields.length - 1))));
            stringBuilder.append(" ? \n)");
        } else if (commandKind == DDlogCommand.Kind.DeleteVal) {
            stringBuilder.append(String.format("delete from %s where %n", dataType));
            final List<String> fieldNames =
                    Arrays.stream(fields).map(s -> String.format(" %s = ?", s.getName())).collect(Collectors.toList());
            stringBuilder.append(String.join(" and ", fieldNames));
        }
        return stringBuilder.toString();
    }

    void createDBTriggers() {
        for (final String entry : baseTables) {
            final String tableName = entry.toUpperCase(Locale.US);
                final String [] operations = {"UPDATE", "INSERT", "DELETE"};
                for (final String op: operations) {
                    final String triggerName = String.format("%s_TRIGGER_%s_%s", key, tableName, op);
                    final String triggerStatement =
                            String.format("CREATE TRIGGER %s BEFORE %s ON %s FOR EACH ROW CALL \"%s\"",
                                          triggerName, op, tableName, triggerClassName);
                    dbCtx.execute(triggerStatement);
                }
        }
    }

    private void receiveUpdateFromDDlog(final DDlogCommand<DDlogRecord> command) {
        final List<Object> objects = new ArrayList<>();
        final DDlogRecord record = command.value();
        final String ddlogRelationName = api.getTableName(command.relid());
        final String tableName = ddlogRelationName.substring(1).toUpperCase(Locale.US);

        // we only hold records for tables we have in the DB and none others.
        if (tableMap.containsKey(tableName)) {
            final Table<? extends Record> table = tableMap.get(tableName);

            int fieldIndex = 0;
            for (final Field<?> field : table.fields()) {
                final Class<?> cls = field.getType();
                final DDlogRecord f = record.getStructField(fieldIndex);
                if (f.isStruct() && f.getStructName().equals("std.None")) {
                    objects.add(null);
                } else {
                    switch (cls.getName()) {
                        case BOOLEAN_TYPE:
                            objects.add(f.getBoolean());
                            break;
                        case INTEGER_TYPE:
                            objects.add(f.getInt());
                            break;
                        case LONG_TYPE:
                            objects.add(f.getInt().longValue());
                            break;
                        case STRING_TYPE:
                            objects.add(f.getString());
                            break;
                        default:
                            throw new RuntimeException(
                                String.format("Unknown datatype %s of field %s in table %s in update received from " +
                                        "DDLog", f.getClass().getName(), field.getName(), tableName));
                    }
                }
                fieldIndex = fieldIndex + 1;
            }
            recordsFromDDLog.add(new LocalDDlogCommand(command.kind(), tableName, objects));
        }
    }

    public void flushUpdates() {
        updater.sendUpdatesToDDlog(mapRecordsFromDB.get(key));

        for (final LocalDDlogCommand command : recordsFromDDLog) {
            final String tableName = command.tableName;

            // for logging
            recordsReceived.putIfAbsent(tableName, 0);
            recordsReceived.put(tableName, recordsReceived.get(tableName) + 1);

            // check if query is already created and if not, create it
            if (!preparedQueries.containsKey(tableName) ||
                    (preparedQueries.containsKey(tableName) &&
                            !preparedQueries.get(tableName).containsKey(command.command))) {
                updatePreparedQueries(tableName, command);
            }
            flush(tableName, command);
        }
        recordsFromDDLog.clear();
        mapRecordsFromDB.get(key).clear();

        recordsReceived.forEach((k, v) -> System.out.println("Key: " + k + " v: " + v));
        recordsReceived.clear();
    }

     private void flush(final String tableName, final LocalDDlogCommand command) {
        try {
            final PreparedStatement query = preparedQueries.get(tableName).get(command.command);
            int fieldIndex = 1;
            for (final Field<?> field: tableMap.get(tableName).fields()) {
                final Object item = command.values.get(fieldIndex - 1);
                if (item == null) {
                    switch (field.getType().getName()) {
                        case BIGINT_TYPE:
                            query.setNull(fieldIndex, Types.BIGINT);
                            break;
                        case LONG_TYPE:
                            query.setNull(fieldIndex, Types.NUMERIC);
                            break;
                        case INTEGER_TYPE:
                            query.setNull(fieldIndex, Types.INTEGER);
                            break;
                        case BOOLEAN_TYPE:
                            query.setNull(fieldIndex, Types.BOOLEAN);
                            break;
                        case STRING_TYPE:
                            query.setNull(fieldIndex, Types.VARCHAR);
                            break;
                        default:
                            throw new RuntimeException(
                                    String.format("Unknown datatype %s of field %s in table %s when " +
                                                  "writing data returned from DDlog to DB",
                                            field.getType().getName(), field.getName(), tableName));
                    }
                }
                else {
                    switch (item.getClass().getName()) {
                        case BIGINT_TYPE:
                            query.setInt(fieldIndex, ((java.math.BigInteger) item).intValue());
                            break;
                        case LONG_TYPE:
                            query.setLong(fieldIndex, (Long) item);
                            break;
                        case INTEGER_TYPE:
                            query.setInt(fieldIndex, (Integer) item);
                            break;
                        case BOOLEAN_TYPE:
                            query.setBoolean(fieldIndex, (Boolean) item);
                            break;
                        case STRING_TYPE:
                            query.setString(fieldIndex, (String) item);
                            break;
                        default:
                            throw new RuntimeException(
                                    String.format("Unknown datatype %s of field %s in table %s when " +
                                                  "writing data returned from DDlog to DB",
                                    item.getClass().getName(), item.getClass().getName(), tableName));
                    }
                }
                fieldIndex = fieldIndex + 1;
            }
            query.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

     private void updatePreparedQueries(final String tableName, final LocalDDlogCommand command) {
        final DDlogCommand.Kind commandKind = command.command;
        preparedQueries.computeIfAbsent(tableName, t -> new HashMap<>());
        if (!preparedQueries.get(tableName).containsKey(commandKind)) {
            // make prepared statement here
            final String preparedQuery = generatePreparedQueryString(tableName, command.command);
            try {
                preparedQueries.get(tableName).put(commandKind, connection.prepareStatement(preparedQuery));
            } catch (final SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void close() {
        try {
            updater.close();
            connection.close();
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }
}