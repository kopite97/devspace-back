package com.kopite.devspace;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** Current-schema fixtures explicitly satisfy the Journal's exclusive Space relationship. */
final class JournalFixtures {
    private JournalFixtures(){}
    static int insert(JdbcTemplate jdbc,String sql,Object... args) {
        UUID space=UUID.randomUUID();
        jdbc.update("insert into document_spaces(id,created_at,updated_at) values(?,now(),now())",space);
        Object[] values=new Object[args.length+1];values[0]=space;System.arraycopy(args,0,values,1,args.length);
        return jdbc.update(sql.replace("journals(","journals(document_space_id,").replace("values(","values(?,"),values);
    }
}
