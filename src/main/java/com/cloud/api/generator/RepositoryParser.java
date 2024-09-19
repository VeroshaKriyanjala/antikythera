package com.cloud.api.generator;

import com.cloud.api.configurations.Settings;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;

import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;

import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RepositoryParser extends ClassProcessor{
    private static final Logger logger = LoggerFactory.getLogger(RepositoryParser.class);
    private Map<MethodDeclaration, String> queries;
    private Connection conn;
    private String dialect;
    private static final String ORACLE = "oracle";
    private static final String POSTGRESQL = "PG";

    public RepositoryParser() throws IOException, SQLException {
        super();
        queries = new HashMap<>();
        Map<String, Object> db = (Map<String, Object>) Settings.getProperty("database");
        if(db != null) {
            String url = db.get("url").toString();
            if(url.contains(ORACLE)) {
                dialect = ORACLE;
            }
            else {
                dialect = POSTGRESQL;
            }
            conn = DriverManager.getConnection(url, db.get("user").toString(), db.get("password").toString());
            try (java.sql.Statement statement = conn.createStatement()) {
                statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + db.get("schema").toString());
            }
        }
    }

    public static void main(String[] args) throws IOException, SQLException {
        Settings.loadConfigMap();
        RepositoryParser parser = new RepositoryParser();
        parser.process("com.csi.vidaplus.ehr.ip.admissionwithcareplane.dao.discharge.DischargeDetailRepository.java");
    }

    /**
     * Count the number of parameters to bind.
     * @param sql an sql statement as a string
     * @return the number of place holder can be 0
     */
    private static int countPlaceholders(String sql) {
        Pattern pattern = Pattern.compile("\\?");
        Matcher matcher = pattern.matcher(sql);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    public void process(String className) throws IOException {
        File f = Paths.get(basePath, className.replaceAll("\\.","/")).toFile();

        CompilationUnit cu = javaParser.parse(f).getResult().orElseThrow(() -> new IllegalStateException("Parse error"));
        process(cu);
    }

    public void process(CompilationUnit cu) throws FileNotFoundException {
        cu.accept(new Visitor(), null);

        var cls = cu.getTypes().get(0).asClassOrInterfaceDeclaration();
        var parents = cls.getExtendedTypes();
        if(!parents.isEmpty() && parents.get(0).toString().startsWith("JpaRepository")) {
            Optional<NodeList<Type>> t = parents.get(0).getTypeArguments();
            if(t.isPresent()) {
                Type entity = t.get().get(0);

                CompilationUnit entityCu = findEntity(entity);

                String table = findTableName(entityCu);

                for (var entry : queries.entrySet()) {
                    executeQuery(entry, entity, table, entityCu);
                }
            }
        }
    }

    private void executeQuery(Map.Entry<MethodDeclaration, String> entry, Type entity, String table, CompilationUnit entityCu) throws FileNotFoundException {
        String query = entry.getValue();
        try {
            query = query.replace(entity.asClassOrInterfaceType().getNameAsString(), table);
            Select stmt = (Select) CCJSqlParserUtil.parse(cleanUp(query));
            convertFieldsToSnakeCase(stmt, entityCu);
            System.out.println(entry.getKey().getNameAsString() +  "\n\t" + stmt);

            String sql = stmt.toString().replaceAll("\\?\\d+", "?");
            if(dialect.equals(ORACLE)) {
                sql = sql.replaceAll("(?i)true", "1")
                        .replaceAll("(?i)false", "0");
            }

            PreparedStatement prep = conn.prepareStatement(sql);

            for(int j = 0 ; j < countPlaceholders(sql) ; j++) {
                prep.setLong(j + 1, 1);
            }

            if(prep.execute()) {
                ResultSet rs = prep.getResultSet();

                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                // Print column names
                for (int i = 1; i <= columnCount; i++) {
                    System.out.print(metaData.getColumnName(i) + "\t");
                }
                System.out.println();

                int i = 0;
                while(rs.next() && i < 10) {
                    for (int j = 1; j <= columnCount; j++) {
                        System.out.print(rs.getString(j) + "\t");
                    }
                    System.out.println();
                    i++;
                }
            }

        } catch (JSQLParserException e) {
            logger.error("\tUnparsable: {}", query);
        } catch (SQLException e) {
            logger.error("\tSQL Error: {}", e.getMessage());
        }
    }

    /**
     * Find the table name from the hibernate entity.
     * Usually the entity will have an annotation giving the actual name of the table.
     * @param entityCu a compilation unit representing the entity
     * @return the table name as a string.
     */
    private static String findTableName(CompilationUnit entityCu) {
        String table = null;

        for(var ann : entityCu.getTypes().get(0).getAnnotations()) {
            if(ann.getNameAsString().equals("Table")) {
                if(ann.isNormalAnnotationExpr()) {
                    for(var pair : ann.asNormalAnnotationExpr().getPairs()) {
                        if(pair.getNameAsString().equals("name")) {
                            table = pair.getValue().toString();
                        }
                    }
                }
                else {
                    table = ann.asSingleMemberAnnotationExpr().getMemberValue().toString();
                }
            }
        }
        return table;
    }

    /**
     * Find and parse the given entity.
     * @param entity a type representing the entity
     * @return a compilation unit
     * @throws FileNotFoundException if the entity cannot be found in the AUT
     */
    private CompilationUnit findEntity(Type entity) throws FileNotFoundException {
        String nameAsString = entity.asClassOrInterfaceType().resolve().describe();
        String fileName = basePath + File.separator + nameAsString.replaceAll("\\.","/") + SUFFIX;
        return javaParser.parse(
                new File(fileName)).getResult().orElseThrow(
                        () -> new IllegalStateException("Parse error")
        );
    }

    /**
     * Java field names need to be converted to snake case to match the table column.
     *
     * @param stmt the sql statement
     * @param entity a compilation unit representing the entity.
     * @throws FileNotFoundException
     */
    private void convertFieldsToSnakeCase(Statement stmt, CompilationUnit entity) throws FileNotFoundException {
        if(stmt instanceof  Select) {
            PlainSelect select = ((Select) stmt).getPlainSelect();

            List<SelectItem<?>> items = select.getSelectItems();
            if(items.size() == 1 && items.get(0).toString().length() == 1) {
                // This is a select * query but because it's an HQL query it appears as SELECT t
                // replace select t with select *
                items.set(0, SelectItem.from(new AllColumns()));
            }
            else {
                // here we deal with general projections
                for (int i = 0; i < items.size(); i++) {
                    SelectItem<?> item = items.get(i);
                    String itemStr = item.toString();
                    String[] parts = itemStr.split("\\.");

                    if (itemStr.contains(".") && parts.length == 2) {
                        String field = parts[1];
                        String snakeCaseField = camelToSnake(field);
                        SelectItem<?> col = SelectItem.from(new Column(parts[0] + "." + snakeCaseField));
                        items.set(i, col);
                    }
                    else {
                        String snakeCaseField = camelToSnake(parts[0]);
                        SelectItem<?> col = SelectItem.from(new Column(snakeCaseField));
                        items.set(i, col);
                    }
                }
            }

            List<Expression> removed = new ArrayList<>();

            if (select.getWhere() != null) {
                select.setWhere(convertExpressionToSnakeCase(select.getWhere(), removed, true));
            }

            if (select.getGroupBy() != null) {
                GroupByElement group = select.getGroupBy();
                List<Expression> groupBy = group.getGroupByExpressions();
                for (int i = 0; i < groupBy.size(); i++) {
                    groupBy.set(i, convertExpressionToSnakeCase(groupBy.get(i), removed, false));
                }
            }

            if (select.getOrderByElements() != null) {
                List<OrderByElement> orderBy = select.getOrderByElements();
                for (int i = 0; i < orderBy.size(); i++) {
                    orderBy.get(i).setExpression(convertExpressionToSnakeCase(orderBy.get(i).getExpression(), removed, false));
                }
            }

            if (select.getHaving() != null) {
                select.setHaving(convertExpressionToSnakeCase(select.getHaving(), removed, false));
            }

            processJoins(entity, select);
        }
    }

    /**
     * HQL joins use entity names instead of table name and column names.
     * We need to replace those with the proper table and column name syntax if we are to execut the
     * query through JDBC.
     * @param entity the primary table or view for the join
     * @param select the select statement
     * @throws FileNotFoundException if we are unable to find related entities.
     */
    private void processJoins(CompilationUnit entity, PlainSelect select) throws FileNotFoundException {
        List<CompilationUnit> units = new ArrayList<>();
        units.add(entity);

        List<Join> joins = select.getJoins();
        if(joins != null) {
            for (int i = 0 ; i < joins.size() ; i++) {
                Join j = joins.get(i);
                if (j.getRightItem() instanceof ParenthesedSelect) {
                    convertFieldsToSnakeCase(((ParenthesedSelect) j.getRightItem()).getSelectBody(), entity);
                } else {
                    FromItem a = j.getRightItem();
                    // the toString() of this will look something like p.dischargeNurseRequest n
                    // from this we need to extract the dischargeNurseRequest
                    String[] parts = a.toString().split("\\.");
                    if (parts.length == 2) {
                        CompilationUnit other = null;
                        // the join may happen against any of the tables that we have encountered so far
                        // hence the need to loop through here.
                        for(CompilationUnit unit : units) {
                            String field = parts[1].split(" ")[0];
                            var x = unit.getType(0).getFieldByName(field);
                            if(x.isPresent()) {
                                var member = x.get();
                                String lhs = null;
                                String rhs = null;

                                // find if there is a join column annotation, that will tell us the column names
                                // to map for the on clause.
                                for (var ann : member.getAnnotations()) {
                                    if (ann.getNameAsString().equals("JoinColumn")) {
                                        if (ann.isNormalAnnotationExpr()) {
                                            for (var pair : ann.asNormalAnnotationExpr().getPairs()) {
                                                if (pair.getNameAsString().equals("name")) {
                                                    lhs = camelToSnake(pair.getValue().toString());
                                                }
                                                if (pair.getNameAsString().equals("referencedColumnName")) {
                                                    rhs = camelToSnake(pair.getValue().toString());
                                                }
                                            }
                                        } else {
                                            lhs = camelToSnake(ann.asSingleMemberAnnotationExpr().getMemberValue().toString());
                                        }
                                        break;
                                    }
                                }

                                other = findEntity(member.getElementType());

                                String tableName = findTableName(other);
                                if(dialect.equals(ORACLE)) {
                                    tableName = tableName.replace("\"","");
                                }

                                var f = j.getFromItem();
                                if (f instanceof Table) {
                                    Table t = new Table(tableName);
                                    t.setAlias(f.getAlias());
                                    j.setFromItem(t);

                                }
                                if(lhs == null || rhs == null) {
                                    // lets roll with an implicit join for now
                                    // todo fix this by figuring out the join column from other annotations
                                    for(var column : other.getType(0).getFields()) {
                                        for(var ann : column.getAnnotations()) {
                                            if(ann.getNameAsString().equals("Id")) {
                                                lhs = camelToSnake(column.getVariable(0).getNameAsString());
                                                rhs = lhs;
                                                break;
                                            }
                                        }
                                    }
                                }
                                if(lhs != null && rhs != null) {
                                    if (dialect.equals(ORACLE)) {
                                        lhs = lhs.replace("\"", "");
                                        rhs = rhs.replace("\"", "");
                                    }
                                    EqualsTo eq = new EqualsTo();
                                    eq.setLeftExpression(new Column(parts[0] + "." + lhs));
                                    eq.setRightExpression(new Column(parts[1].split(" ")[1] + "." + rhs));
                                    j.getOnExpressions().add(eq);
                                }

                            }
                        }
                        // if we have discovered a new entity add it to our collection for looking up
                        // join fields in the next one
                        if(other != null) {
                            units.add(other);
                        }
                    }
                }
            }
        }
    }

    /**
     * Recursively convert field names in expressions to snake case
     *
     * We violate SRP by also cleaning up some of the fields in the where clause
     * @param expr
     * @return the converted expression
     */
    private Expression convertExpressionToSnakeCase(Expression expr, List<Expression> removed, boolean where) {
        if (expr instanceof AndExpression) {
            AndExpression andExpr = (AndExpression) expr;
            andExpr.setLeftExpression(convertExpressionToSnakeCase(andExpr.getLeftExpression(), removed, where));
            andExpr.setRightExpression(convertExpressionToSnakeCase(andExpr.getRightExpression(), removed, where));
        }
        else if (expr instanceof  InExpression) {
            InExpression ine = (InExpression) expr;
            Column col = (Column) ine.getLeftExpression();
            if(where &&
                    !(col.getColumnName().equals("hospitalId") || col.getColumnName().equals("hospitalGroupId"))) {
                removed.add(ine.getLeftExpression());
                ine.setLeftExpression(new StringValue("1"));
                ExpressionList<Expression> rightExpression = new ExpressionList<>();
                rightExpression.add(new StringValue("1"));
                ine.setRightExpression(rightExpression);
            }
            else {
                ine.setLeftExpression(convertExpressionToSnakeCase(ine.getLeftExpression(), removed, where));
            }
        }
        else if (expr instanceof IsNullExpression) {
            IsNullExpression isNull = (IsNullExpression) expr;
            isNull.setLeftExpression(convertExpressionToSnakeCase(isNull.getLeftExpression(), removed, where));
        }
        else if (expr instanceof ParenthesedExpressionList) {
            ParenthesedExpressionList pel = (ParenthesedExpressionList) expr;
            for(int i = 0 ; i < pel.size() ; i++) {
                pel.getExpressions().set(i, convertExpressionToSnakeCase((Expression) pel.get(i), removed, where));
            }
        }
        else if (expr instanceof CaseExpression) {
            CaseExpression ce = (CaseExpression) expr;
            for(int i = 0; i < ce.getWhenClauses().size(); i++) {
                WhenClause when = ce.getWhenClauses().get(i);
                when.setWhenExpression(convertExpressionToSnakeCase(when.getWhenExpression(), removed, where));
                when.setThenExpression(convertExpressionToSnakeCase(when.getThenExpression(), removed, where));
            }

        }
        else if (expr instanceof WhenClause) {
            WhenClause wh = (WhenClause) expr;
            wh.setWhenExpression(convertExpressionToSnakeCase(wh.getWhenExpression(), removed, where));
        }
        else if (expr instanceof Function) {
            Function function = (Function) expr;
            ExpressionList params = (ExpressionList) function.getParameters().getExpressions();
            if(params != null) {
                for (int i = 0; i < params.size(); i++) {
                    params.getExpressions().set(i, convertExpressionToSnakeCase((Expression) params.get(i), removed, where));
                }
            }
        }
        else if (expr instanceof ComparisonOperator) {
            // Most where clause and having clause stuff have their leaves here.
            ComparisonOperator compare = (ComparisonOperator) expr;

            Expression left = compare.getLeftExpression();
            Expression right = compare.getRightExpression();

            if(left instanceof Column && right instanceof JdbcParameter || right instanceof JdbcNamedParameter) {
                // we have a comparison so this is vary likely to be a part of the where clause.
                // our object is to run a query to identify likely data. So removing as many
                // components from the where clause is the way to go
                Column col = (Column) left;
                if(where) {
                    if (!(col.getColumnName().equals("hospitalId") || col.getColumnName().equals("hospitalGroupId"))) {
                        removed.add(left);

                        compare.setLeftExpression(new StringValue("1"));
                        compare.setRightExpression(new StringValue("1"));
                        return expr;
                    }
                }

                // Because we are not sure in which horder the hospital id and the group id may have been
                // specified in the query we set them here when we encounter it.
                if(col.getColumnName().equals("hospitalId")) {
                    compare.setRightExpression(new LongValue("59"));
                    compare.setLeftExpression(convertExpressionToSnakeCase(left, removed, where));
                    return expr;
                }
                else if(col.getColumnName().equals("hospitalGroupId")) {
                    compare.setRightExpression(new LongValue("58"));
                    compare.setLeftExpression(convertExpressionToSnakeCase(left, removed, where));
                    return expr;
                }
            }

            // common fall through for everything
            compare.setRightExpression(convertExpressionToSnakeCase(right, removed, where));
            compare.setLeftExpression(convertExpressionToSnakeCase(left, removed, where));

        }
        else if (expr instanceof BinaryExpression) {
            BinaryExpression binaryExpr = (BinaryExpression) expr;
            binaryExpr.setLeftExpression(convertExpressionToSnakeCase(binaryExpr.getLeftExpression(), removed, where));
            binaryExpr.setRightExpression(convertExpressionToSnakeCase(binaryExpr.getRightExpression(), removed, where));
        } else if (expr instanceof Column) {
            Column column = (Column) expr;
            String columnName = column.getColumnName();

            String snakeCaseField = camelToSnake(columnName);
            column.setColumnName(snakeCaseField);
            return column;
        }
        return expr;
    }


    public static String camelToSnake(String str) {
        if(str.toLowerCase().equals("patientpomr")) {
            return str;
        }
        return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }

    class Visitor extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(MethodDeclaration n, Void arg) {
            super.visit(n, arg);
            String query = null;

            for (var ann : n.getAnnotations()) {
                if (ann.getNameAsString().equals("Query")) {
                    if (ann.isSingleMemberAnnotationExpr()) {
                        query = ann.asSingleMemberAnnotationExpr().getMemberValue().toString();
                    } else if (ann.isNormalAnnotationExpr()) {
                        boolean nt = false;
                        for (var pair : ann.asNormalAnnotationExpr().getPairs()) {
                            if (pair.getNameAsString().equals("nativeQuery")) {
                                if (pair.getValue().toString().equals("true")) {
                                    nt = true;
                                    System.out.println("\tNative Query");
                                } else {
                                    System.out.println("\tJPQL Query");
                                }
                            }
                        }
                        for (var pair : ann.asNormalAnnotationExpr().getPairs()) {
                            if (pair.getNameAsString().equals("value")) {
                                query = pair.getValue().toString();
                            }
                        }
                    }
                    break;
                }
            }

            if(query != null) {
                query = cleanUp(query);
                queries.put(n, query);
            }
            else {
                // todo the query needs to be built from the method name
            }
        }
    }

    /**
     * CLean up method to be called before handing over to JSQL
     * @param sql
     * @return the cleaned up sql as a string
     */
    private String cleanUp(String sql) {
        // If a JPA query is using a projection, we will have a new keyword immediately after the select
        // JSQL does not recognize this. So we will remove everything from the NEW keyword to the FROM
        // keyword and replace it with the '*' character.
        // Use case-insensitive regex to find and replace the NEW keyword and the FROM keyword
        Pattern pattern = Pattern.compile("new\\s+.*?\\s+from\\s+", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            sql = matcher.replaceAll(" * from ");
        }

        // Remove '+' signs only when they have spaces and a quotation mark on either side
        sql = sql.replaceAll("\"\\s*\\+\\s*\"", " ");

        // Remove quotation marks
        sql = sql.replace("\"", "");

        return sql;
    }

}

