package com.vmware.dcm;

import com.vmware.ddlog.DDlogJooqProvider;
import com.vmware.ddlog.ir.DDlogProgram;
import com.vmware.ddlog.translator.Translator;
import com.vmware.ddlog.util.sql.CalciteSqlStatement;
import com.vmware.ddlog.util.sql.CalciteToH2Translator;
import com.vmware.ddlog.util.sql.CalciteToPrestoTranslator;
import com.vmware.ddlog.util.sql.H2SqlStatement;
import ddlogapi.DDlogAPI;
import ddlogapi.DDlogException;
import org.apache.commons.io.FileUtils;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamCastMode;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class DDlogDBConnectionPool implements IConnectionPool {

    private final List<String> scopedViews;

    private DDlogJooqProvider provider;
    private final String ddlogFile;

    public DDlogDBConnectionPool() {
        this.ddlogFile = null;
        scopedViews = new ArrayList<>();
    }

    public DDlogDBConnectionPool(String ddlogFile) {
        this.ddlogFile = ddlogFile;
        this.scopedViews = new ArrayList<>();
    }

    @Override
    public DSLContext getConnectionToDb() {
        final MockConnection connection = new MockConnection(provider);
        return DSL.using(connection, SQLDialect.H2, new Settings()
                .withExecuteLogging(false)
                .withParamCastMode(ParamCastMode.NEVER)
                .withRenderCatalog(false)
                .withRenderSchema(false));
    }

    @Override
    public DSLContext getDataConnectionToDb() {
        return provider.getDslContext();
    }

    public DDlogJooqProvider getProvider() {
        return provider;
    }

    public void addScopedViews(List<String> statements) {
        scopedViews.addAll(statements);
    }

    private static void compileAndLoad(final List<CalciteSqlStatement> ddl, final List<String> createIndexStatements,
                                       final String ddlogFile)
            throws IOException, DDlogException {
        final String fileName = "/tmp/program.dl";
        if (ddlogFile != null) {
            // don't compile, instead use this ddlogFile
            // Copy ddlogFile contents to fileName
            FileUtils.copyFile(new File(ddlogFile), new File(fileName));
        } else {
            final Translator t = new Translator(null);
            CalciteToPrestoTranslator ctopTranslator = new CalciteToPrestoTranslator();
            ddl.forEach(x -> t.translateSqlStatement(ctopTranslator.toPresto(x)));
            createIndexStatements.forEach(t::translateCreateIndexStatement);

            final DDlogProgram dDlogProgram = t.getDDlogProgram();
            File tmp = new File(fileName);
            BufferedWriter bw = new BufferedWriter(new FileWriter(tmp));
            bw.write(dDlogProgram.toString());
            bw.close();
        }
        DDlogAPI.CompilationResult result = new DDlogAPI.CompilationResult(true);
        final String ddlogHome = System.getenv("DDLOG_HOME");
        assertNotNull(ddlogHome);
        DDlogAPI.compileDDlogProgram(fileName, result, ddlogHome + "/lib", ddlogHome + "/sql/lib");
        if (!result.isSuccess())
            throw new RuntimeException("Failed to compile ddlog program");
        DDlogAPI.loadDDlog();
    }

    private void setupDDlog(boolean seal) {
        try {
            List<String> tables = DDlogDBViews.getSchema();
            CalciteToH2Translator translator = new CalciteToH2Translator();

            // The `create index` statements are for H2 and not for the DDlog backend
            List<String> createIndexStatements = new ArrayList<>();
            List<CalciteSqlStatement> tablesInCalcite = new ArrayList<>();

            tables.forEach(x -> {
                if (x.startsWith("create index")) {
                    createIndexStatements.add(x);
                } else {
                    tablesInCalcite.add(new CalciteSqlStatement((x)));
                }
            });

            scopedViews.forEach(x -> tablesInCalcite.add(new CalciteSqlStatement(x)));

            DDlogAPI ddlogAPI = null;
            if (seal) {
                compileAndLoad(tablesInCalcite, createIndexStatements, ddlogFile);

                ddlogAPI = new DDlogAPI(1, false);
            }

            // Initialise the data provider
            final DDlogJooqProvider provider = new DDlogJooqProvider(ddlogAPI,
                    Stream.concat(
                            tablesInCalcite.stream().map(translator::toH2),
                            createIndexStatements.stream().map(H2SqlStatement::new)).collect(Collectors.toList()));
            this.provider = provider;
        } catch (Exception e) {
            throw new RuntimeException("Could not set up DDlog backend: " + e.getMessage());
        }
    }
    public void buildDDlog(boolean seal) {
        setupDDlog(seal);
    }
}
