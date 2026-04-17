package cn.suhoan.anaxa.sdk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用于构建搜索接口里的 payload 过滤表达式。
 *
 * <p>服务端本身支持直接传 JSON Map；这个 Builder 的目的不是“扩展协议”，
 * 而是把那些容易拼错的操作符结构变成类型更友好的 Java API。
 */
public final class PayloadFilterBuilder {
    private final List<Map<String, Object>> clauses = new ArrayList<>();

    private PayloadFilterBuilder() {
    }

    /**
     * 创建一个新的过滤构建器。
     */
    public static PayloadFilterBuilder filter() {
        return new PayloadFilterBuilder();
    }

    /**
     * 添加一个精确匹配条件，例如 {@code tenant = "team-a"}。
     */
    public PayloadFilterBuilder eq(String field, Object value) {
        clauses.add(singleFieldClause(field, value));
        return this;
    }

    /**
     * 添加一个 {@code $in} 条件。
     */
    public PayloadFilterBuilder in(String field, Collection<?> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        LinkedHashMap<String, Object> expression = new LinkedHashMap<>();
        expression.put("$in", List.copyOf(values));
        clauses.add(singleFieldClause(field, expression));
        return this;
    }

    /**
     * 添加一个 {@code $contains} 条件。
     *
     * <p>适合标签数组、关键词数组等“集合包含某值”的过滤。
     */
    public PayloadFilterBuilder contains(String field, Object value) {
        LinkedHashMap<String, Object> expression = new LinkedHashMap<>();
        expression.put("$contains", value);
        clauses.add(singleFieldClause(field, expression));
        return this;
    }

    /**
     * 添加范围条件。
     *
     * <p>四个参数只要提供至少一个即可；未提供的边界不会写入最终表达式。
     */
    public PayloadFilterBuilder range(String field, Object gt, Object gte, Object lt, Object lte) {
        LinkedHashMap<String, Object> expression = new LinkedHashMap<>();
        if (gt != null) {
            expression.put("$gt", gt);
        }
        if (gte != null) {
            expression.put("$gte", gte);
        }
        if (lt != null) {
            expression.put("$lt", lt);
        }
        if (lte != null) {
            expression.put("$lte", lte);
        }
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("At least one range bound must be provided");
        }
        clauses.add(singleFieldClause(field, expression));
        return this;
    }

    /**
     * 直接附加一段原始过滤子句。
     *
     * <p>当 SDK 尚未提供某个便捷方法时，可以先通过这个入口透传自定义结构。
     */
    public PayloadFilterBuilder raw(Map<String, Object> clause) {
        if (clause == null || clause.isEmpty()) {
            throw new IllegalArgumentException("clause must not be empty");
        }
        clauses.add(immutableClause(clause));
        return this;
    }

    /**
     * 添加一个 {@code $and} 复合子句。
     */
    public PayloadFilterBuilder and(PayloadFilterBuilder... builders) {
        clauses.add(logicalClause("$and", builders));
        return this;
    }

    /**
     * 添加一个 {@code $or} 复合子句。
     */
    public PayloadFilterBuilder or(PayloadFilterBuilder... builders) {
        clauses.add(logicalClause("$or", builders));
        return this;
    }

    /**
     * 添加一个 {@code $not} 子句。
     */
    public PayloadFilterBuilder not(PayloadFilterBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        Map<String, Object> built = builder.build();
        if (built.isEmpty()) {
            throw new IllegalArgumentException("builder must not be empty");
        }
        return raw(Map.of("$not", built));
    }

    /**
     * 构建最终的过滤表达式。
     *
     * <p>如果没有任何条件，会返回空 Map；这样可以直接交给 {@code SearchRequest} 使用。
     */
    public Map<String, Object> build() {
        if (clauses.isEmpty()) {
            return Map.of();
        }
        if (clauses.size() == 1) {
            return immutableClause(clauses.get(0));
        }
        return Map.of("$and", clauses.stream().map(PayloadFilterBuilder::immutableClause).toList());
    }

    private static Map<String, Object> logicalClause(String operator, PayloadFilterBuilder... builders) {
        Objects.requireNonNull(builders, "builders");
        if (builders.length == 0) {
            throw new IllegalArgumentException("builders must not be empty");
        }
        List<Map<String, Object>> built = new ArrayList<>(builders.length);
        for (PayloadFilterBuilder builder : builders) {
            Objects.requireNonNull(builder, "builder");
            Map<String, Object> expression = builder.build();
            if (expression.isEmpty()) {
                throw new IllegalArgumentException("builder must not be empty");
            }
            built.add(expression);
        }
        return Map.of(operator, List.copyOf(built));
    }

    private static Map<String, Object> singleFieldClause(String field, Object expression) {
        String normalizedField = Objects.requireNonNull(field, "field").trim();
        if (normalizedField.isEmpty()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        LinkedHashMap<String, Object> clause = new LinkedHashMap<>();
        clause.put(normalizedField, expression);
        return clause;
    }

    private static Map<String, Object> immutableClause(Map<String, Object> clause) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(clause));
    }
}
