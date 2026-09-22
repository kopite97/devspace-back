import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

class LocalValidation {
    public static void main(String[] args) throws Exception {
        Properties env = new Properties();
        try (var in = Files.newBufferedReader(Path.of(".env"))) { env.load(in); }
        String url = env.getProperty("DB_URL");
        for (String key : List.of("DB_HOST","DB_PORT","DB_NAME")) url=url.replace("${"+key+"}",env.getProperty(key));
        if (url == null || !(url.startsWith("jdbc:postgresql://localhost:5432/") || url.startsWith("jdbc:postgresql://127.0.0.1:5432/")))
            throw new IllegalStateException("Expected explicitly inventoried loopback database");
        url += (url.contains("?") ? "&" : "?") + "options=-c%20default_transaction_read_only%3Don";
        var flyway = Flyway.configure().dataSource(url,env.getProperty("DB_USERNAME"),env.getProperty("DB_PASSWORD"))
            .locations("filesystem:src/main/resources/db/migration","filesystem:src/main/migration-stages/cutover","filesystem:src/main/migration-stages/contract")
            .loggers("slf4j").load();
        // Validate only: never migrate, baseline, repair or clean the real DB.
        var validation = flyway.validateWithResult();
        if (!validation.validationSuccessful || validation.validateCount != 18) throw new IllegalStateException("Local Flyway validation failed");
        System.out.println("FLYWAY_VALIDATION=18 unchanged migrations");
        try (var c=DriverManager.getConnection(url,env.getProperty("DB_USERNAME"),env.getProperty("DB_PASSWORD"))) {
            c.setReadOnly(true); c.setAutoCommit(false);
            try (var s=c.createStatement()) {
                s.execute("set local statement_timeout='5s'");
                try(var r=s.executeQuery("select tablename from pg_tables where schemaname='public' order by tablename")) {
                    var tables=new ArrayList<String>(); while(r.next()) tables.add(r.getString(1));
                    for(String table:tables) try(var q=c.createStatement();var rows=q.executeQuery("select count(*), md5(coalesce(string_agg(to_jsonb(t)::text, '' order by to_jsonb(t)::text),'')) from \""+table.replace("\"","\"\"")+"\" t")) {
                        rows.next();System.out.println(table+" count="+rows.getLong(1)+" digest="+rows.getString(2));
                    }
                }
            } c.rollback();
        }
        if (args.length>0 && args[0].equals("--boot")) {
            try(var app=new org.springframework.boot.builder.SpringApplicationBuilder(com.kopite.devspace.DevspaceApplication.class).run(
                "--spring.config.location=file:src/test/resources/widget-isolated-test.yml","--server.port=0",
                "--spring.datasource.url="+url,"--spring.datasource.username="+env.getProperty("DB_USERNAME"),"--spring.datasource.password="+env.getProperty("DB_PASSWORD"),
                "--spring.flyway.target=18","--logging.level.root=ERROR")) {
                var jdbc=app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                var users=jdbc.queryForList("select owner_user_id from workspaces",java.util.UUID.class);
                int layouts=0,widgets=0;
                for(var user:users) {
                    var layout=app.getBean(com.kopite.devspace.dashboard.application.HomeLayoutQueryService.class).get(user);
                    if(layout.initialized())layouts++;
                    for(var widget:layout.widgets()) {
                        app.getBean(com.kopite.devspace.widget.application.WidgetDataService.class).get(user,widget.id(),null);widgets++;
                    }
                }
                System.out.println("LOCAL_READ_ONLY_STARTUP_PASS layouts="+layouts+" widgetPayloads="+widgets);
            }
        }
    }
}
