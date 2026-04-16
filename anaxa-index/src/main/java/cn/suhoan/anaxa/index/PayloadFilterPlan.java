package cn.suhoan.anaxa.index;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PayloadFilterPlan {
    private final BitSet candidateOrdinals;
    private final Expression expression;
    private final boolean usesIndex;

    private PayloadFilterPlan(BitSet candidateOrdinals, Expression expression, boolean usesIndex) {
        this.candidateOrdinals = candidateOrdinals;
        this.expression = expression;
        this.usesIndex = usesIndex;
    }

    static PayloadFilterPlan compile(Map<String, Object> filter, PayloadFilterIndex index, PayloadColumnStore columnStore) {
        if (filter == null || filter.isEmpty()) {
            return new PayloadFilterPlan(null, payload -> true, false);
        }
        Expression expression = parseExpression(filter);
        return new PayloadFilterPlan(expression.candidates(index, columnStore), expression, expression.usesIndex());
    }

    BitSet candidateOrdinals() {
        return candidateOrdinals == null ? null : (BitSet) candidateOrdinals.clone();
    }

    boolean matches(Map<String, Object> payload) {
        return expression.matches(payload);
    }

    boolean usesIndex() {
        return usesIndex;
    }

    private static Expression parseExpression(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Filter expressions must be JSON objects");
        }

        ArrayList<Expression> expressions = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Filter keys must be strings");
            }
            Object value = entry.getValue();
            switch (key) {
                case "$and" -> expressions.add(parseAnd(value));
                case "$or" -> expressions.add(parseOr(value));
                case "$not" -> expressions.add(new NotExpression(parseExpression(value)));
                default -> expressions.add(parseFieldExpression(key, value));
            }
        }
        return combineAnd(expressions);
    }

    private static Expression parseAnd(Object value) {
        return new AndExpression(parseExpressionList(value));
    }

    private static Expression parseOr(Object value) {
        return new OrExpression(parseExpressionList(value));
    }

    private static List<Expression> parseExpressionList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Boolean filter clauses require a non-empty array");
        }
        ArrayList<Expression> expressions = new ArrayList<>(list.size());
        for (Object element : list) {
            expressions.add(parseExpression(element));
        }
        return List.copyOf(expressions);
    }

    private static Expression parseFieldExpression(String field, Object value) {
        if (value instanceof Map<?, ?> map && isOperatorMap(map)) {
            ArrayList<Expression> predicates = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String operator = Objects.toString(entry.getKey(), null);
                if (operator == null) {
                    throw new IllegalArgumentException("Filter operators must be strings");
                }
                predicates.add(parseOperator(field, operator, entry.getValue()));
            }
            return combineAnd(predicates);
        }
        if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
            ArrayList<Expression> predicates = new ArrayList<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Nested filter keys must be strings");
                }
                predicates.add(parseFieldExpression(field + "." + key, entry.getValue()));
            }
            return combineAnd(predicates);
        }
        return new EqualsExpression(field, value);
    }

    private static boolean isOperatorMap(Map<?, ?> map) {
        for (Object key : map.keySet()) {
            if (key instanceof String text && text.startsWith("$")) {
                return true;
            }
        }
        return false;
    }

    private static Expression parseOperator(String field, String operator, Object value) {
        return switch (operator) {
            case "$eq" -> new EqualsExpression(field, value);
            case "$in" -> new InExpression(field, requireList(operator, value));
            case "$contains" -> new ContainsExpression(field, value);
            case "$gt" -> new RangeExpression(field, value, false, null, false);
            case "$gte" -> new RangeExpression(field, value, true, null, false);
            case "$lt" -> new RangeExpression(field, null, false, value, false);
            case "$lte" -> new RangeExpression(field, null, false, value, true);
            default -> throw new IllegalArgumentException("Unsupported filter operator: " + operator);
        };
    }

    private static List<?> requireList(String operator, Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(operator + " requires an array value");
        }
        return list;
    }

    private static Expression combineAnd(List<Expression> expressions) {
        if (expressions.isEmpty()) {
            return payload -> true;
        }
        if (expressions.size() == 1) {
            return expressions.getFirst();
        }
        return new AndExpression(expressions);
    }

    private static Object resolveField(Map<String, Object> payload, String field) {
        Object current = payload;
        for (String token : field.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(token);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static boolean containsValue(Object candidate, Object expected) {
        if (candidate instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (Objects.equals(element, expected)) {
                    return true;
                }
            }
            return false;
        }
        if (candidate instanceof Object[] array) {
            for (Object element : array) {
                if (Objects.equals(element, expected)) {
                    return true;
                }
            }
            return false;
        }
        if (candidate instanceof String text && expected instanceof String part) {
            return text.contains(part);
        }
        return false;
    }

    private static int compareValues(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        if (left instanceof Comparable<?> comparable && left.getClass().isInstance(right)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> typedComparable = (Comparable<Object>) comparable;
            return typedComparable.compareTo(right);
        }
        throw new IllegalArgumentException("Range filters require comparable values of the same type");
    }

    @FunctionalInterface
    private interface Expression {
        boolean matches(Map<String, Object> payload);

        default BitSet candidates(PayloadFilterIndex index, PayloadColumnStore columnStore) {
            return null;
        }

        default boolean usesIndex() {
            return false;
        }
    }

    private record AndExpression(List<Expression> children) implements Expression {
        @Override
        public boolean matches(Map<String, Object> payload) {
            for (Expression child : children) {
                if (!child.matches(payload)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public BitSet candidates(PayloadFilterIndex index, PayloadColumnStore columnStore) {
            BitSet candidates = null;
            for (Expression child : children) {
                BitSet childCandidates = child.candidates(index, columnStore);
                if (childCandidates == null) {
                    continue;
                }
                if (candidates == null) {
                    candidates = childCandidates;
                } else {
                    candidates.and(childCandidates);
                    if (candidates.isEmpty()) {
                        return candidates;
                    }
                }
            }
            return candidates;
        }

        @Override
        public boolean usesIndex() {
            return children.stream().anyMatch(Expression::usesIndex);
        }
    }

    private record OrExpression(List<Expression> children) implements Expression {
        @Override
        public boolean matches(Map<String, Object> payload) {
            for (Expression child : children) {
                if (child.matches(payload)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public BitSet candidates(PayloadFilterIndex index, PayloadColumnStore columnStore) {
            BitSet candidates = null;
            for (Expression child : children) {
                BitSet childCandidates = child.candidates(index, columnStore);
                if (childCandidates == null) {
                    return null;
                }
                if (candidates == null) {
                    candidates = childCandidates;
                } else {
                    candidates.or(childCandidates);
                }
            }
            return candidates;
        }

        @Override
        public boolean usesIndex() {
            return children.stream().anyMatch(Expression::usesIndex);
        }
    }

    private record NotExpression(Expression child) implements Expression {
        @Override
        public boolean matches(Map<String, Object> payload) {
            return !child.matches(payload);
        }

        @Override
        public BitSet candidates(PayloadFilterIndex index, PayloadColumnStore columnStore) {
            BitSet childCandidates = child.candidates(index, columnStore);
            if (childCandidates == null) {
                return null;
            }
            BitSet all = index.allOrdinals();
            all.andNot(childCandidates);
            return all;
        }

        @Override
        public boolean usesIndex() {
            return child.usesIndex();
        }
    }

    private record EqualsExpression(String field, Object expected) implements Expression {
        @Override
        public boolean matches(Map<String, Object> payload) {
            return Objects.equals(resolveField(payload, field), expected);
        }

        @Override
        public BitSet candidates(PayloadFilterIndex index, PayloadColumnStore columnStore) {
            return index.exactMatch(field, expected);
        }

        @Override
        public boolean usesIndex() {
            return true;
        }
    }

    private record InExpression(String field, List<?> expectedValues) implements Expression {
        @Override
        public boolean matches(Map<String, Object> payload) {
            Object value = resolveField(payload, field);
            for (Object expected : expectedValues) {
                if (Objects.equals(value, expected)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public BitSet candidates(PayloadFilterIndex index, PayloadColumnStore columnStore) {
            BitSet candidates = new BitSet(index.size());
            for (Object expected : expectedValues) {
                candidates.or(index.exactMatch(field, expected));
            }
            return candidates;
        }

        @Override
        public boolean usesIndex() {
            return true;
        }
    }

    private record ContainsExpression(String field, Object expected) implements Expression {
        @Override
        public boolean matches(Map<String, Object> payload) {
            return containsValue(resolveField(payload, field), expected);
        }

        @Override
        public BitSet candidates(PayloadFilterIndex index, PayloadColumnStore columnStore) {
            return index.exactMatch(field, expected);
        }

        @Override
        public boolean usesIndex() {
            return true;
        }
    }

    private record RangeExpression(
            String field,
            Object lowerBound,
            boolean includeLowerBound,
            Object upperBound,
            boolean includeUpperBound
    ) implements Expression {
        @Override
        public BitSet candidates(PayloadFilterIndex index, PayloadColumnStore columnStore) {
            return columnStore == null
                    ? null
                    : columnStore.rangeMatch(field, lowerBound, includeLowerBound, upperBound, includeUpperBound);
        }

        @Override
        public boolean matches(Map<String, Object> payload) {
            Object actual = resolveField(payload, field);
            if (actual == null) {
                return false;
            }
            if (lowerBound != null) {
                int comparison = compareValues(actual, lowerBound);
                if (comparison < 0 || (!includeLowerBound && comparison == 0)) {
                    return false;
                }
            }
            if (upperBound != null) {
                int comparison = compareValues(actual, upperBound);
                if (comparison > 0 || (!includeUpperBound && comparison == 0)) {
                    return false;
                }
            }
            return true;
        }
    }
}
