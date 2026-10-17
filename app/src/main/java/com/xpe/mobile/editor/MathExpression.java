package com.xpe.mobile.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small dependency-free expression compiler used by the manual's Complex Move tool. */
public final class MathExpression {
    public interface Compiled {
        double evaluate(double t);
    }

    private interface Node {
        double evaluate(double t);
    }

    private static final int MAX_EXPRESSION_LENGTH = 1024;
    private static final int MAX_PARSE_DEPTH = 96;

    private MathExpression() {
    }

    public static Compiled compile(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("expression is required");
        }
        String normalized = source.replace("$", "").trim();
        if (normalized.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("expression is too long");
        }
        Parser parser = new Parser(normalized);
        Node root = parser.parse();
        return t -> {
            if (!Double.isFinite(t)) throw new IllegalArgumentException("t must be finite");
            double value = root.evaluate(t);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("expression result is not finite");
            }
            return value;
        };
    }

    private static final class Parser {
        private final String source;
        private int position;
        private int depth;

        Parser(String source) {
            this.source = source;
        }

        Node parse() {
            Node node = parseAdditive();
            skipWhitespace();
            if (!atEnd()) throw error("unexpected character");
            return node;
        }

        private Node parseAdditive() {
            enter();
            try {
                Node node = parseMultiplicative();
                while (true) {
                    if (take('+')) {
                        Node left = node;
                        Node right = parseMultiplicative();
                        node = t -> left.evaluate(t) + right.evaluate(t);
                    } else if (take('-')) {
                        Node left = node;
                        Node right = parseMultiplicative();
                        node = t -> left.evaluate(t) - right.evaluate(t);
                    } else {
                        return node;
                    }
                }
            } finally {
                leave();
            }
        }

        private Node parseMultiplicative() {
            Node node = parseUnary();
            while (true) {
                if (take('*')) {
                    Node left = node;
                    Node right = parseUnary();
                    node = t -> left.evaluate(t) * right.evaluate(t);
                } else if (take('/')) {
                    Node left = node;
                    Node right = parseUnary();
                    node = t -> left.evaluate(t) / right.evaluate(t);
                } else if (take('%')) {
                    Node left = node;
                    Node right = parseUnary();
                    node = t -> left.evaluate(t) % right.evaluate(t);
                } else {
                    return node;
                }
            }
        }

        private Node parseUnary() {
            if (take('+')) return parseUnary();
            if (take('-')) {
                Node value = parseUnary();
                return t -> -value.evaluate(t);
            }
            return parsePower();
        }

        private Node parsePower() {
            Node base = parsePrimary();
            if (!take('^')) return base;
            Node exponent = parseUnary();
            return t -> Math.pow(base.evaluate(t), exponent.evaluate(t));
        }

        private Node parsePrimary() {
            skipWhitespace();
            if (take('(')) {
                Node value = parseAdditive();
                require(')');
                return value;
            }
            if (atEnd()) throw error("value expected");
            char current = source.charAt(position);
            if (Character.isDigit(current) || current == '.') return parseNumber();
            if (Character.isLetter(current) || current == '_') return parseIdentifier();
            throw error("value expected");
        }

        private Node parseNumber() {
            int start = position;
            boolean digit = false;
            while (!atEnd() && Character.isDigit(source.charAt(position))) {
                position++;
                digit = true;
            }
            if (!atEnd() && source.charAt(position) == '.') {
                position++;
                while (!atEnd() && Character.isDigit(source.charAt(position))) {
                    position++;
                    digit = true;
                }
            }
            if (!digit) throw error("invalid number");
            if (!atEnd() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                int exponent = position++;
                if (!atEnd() && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
                    position++;
                }
                int exponentStart = position;
                while (!atEnd() && Character.isDigit(source.charAt(position))) position++;
                if (position == exponentStart) {
                    position = exponent;
                    throw error("invalid exponent");
                }
            }
            double value;
            try {
                value = Double.parseDouble(source.substring(start, position));
            } catch (NumberFormatException exception) {
                throw error("invalid number");
            }
            if (!Double.isFinite(value)) throw error("number is not finite");
            return t -> value;
        }

        private Node parseIdentifier() {
            int start = position;
            while (!atEnd()) {
                char value = source.charAt(position);
                if (!Character.isLetterOrDigit(value) && value != '_') break;
                position++;
            }
            String name = source.substring(start, position).toLowerCase(Locale.US);
            skipWhitespace();
            if (!take('(')) {
                switch (name) {
                    case "t": return t -> t;
                    case "pi": return t -> Math.PI;
                    case "e": return t -> Math.E;
                    default: throw error("unknown identifier " + name);
                }
            }

            List<Node> arguments = new ArrayList<>();
            skipWhitespace();
            if (!take(')')) {
                do {
                    arguments.add(parseAdditive());
                } while (take(','));
                require(')');
            }
            validateArity(name, arguments.size());
            return t -> evaluateFunction(name, arguments, t);
        }

        private void enter() {
            if (++depth > MAX_PARSE_DEPTH) throw error("expression is too deeply nested");
        }

        private void leave() {
            depth--;
        }

        private boolean take(char expected) {
            skipWhitespace();
            if (atEnd() || source.charAt(position) != expected) return false;
            position++;
            return true;
        }

        private void require(char expected) {
            if (!take(expected)) throw error("expected '" + expected + "'");
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(source.charAt(position))) position++;
        }

        private boolean atEnd() {
            return position >= source.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + position);
        }
    }

    private static void validateArity(String name, int count) {
        switch (name) {
            case "min":
            case "max":
                if (count >= 2) return;
                break;
            case "pow":
            case "atan2":
                if (count == 2) return;
                break;
            case "clamp":
                if (count == 3) return;
                break;
            case "sin":
            case "cos":
            case "tan":
            case "asin":
            case "acos":
            case "atan":
            case "sinh":
            case "cosh":
            case "tanh":
            case "sqrt":
            case "abs":
            case "floor":
            case "ceil":
            case "round":
            case "exp":
            case "ln":
            case "log":
            case "log10":
            case "sign":
            case "signum":
                if (count == 1) return;
                break;
            default:
                throw new IllegalArgumentException("unknown function " + name);
        }
        throw new IllegalArgumentException("invalid argument count for " + name);
    }

    private static double evaluateFunction(String name, List<Node> arguments, double t) {
        double first = arguments.isEmpty() ? 0.0 : arguments.get(0).evaluate(t);
        switch (name) {
            case "sin": return Math.sin(first);
            case "cos": return Math.cos(first);
            case "tan": return Math.tan(first);
            case "asin": return Math.asin(first);
            case "acos": return Math.acos(first);
            case "atan": return Math.atan(first);
            case "atan2": return Math.atan2(first, arguments.get(1).evaluate(t));
            case "sinh": return Math.sinh(first);
            case "cosh": return Math.cosh(first);
            case "tanh": return Math.tanh(first);
            case "sqrt": return Math.sqrt(first);
            case "abs": return Math.abs(first);
            case "floor": return Math.floor(first);
            case "ceil": return Math.ceil(first);
            case "round": return Math.rint(first);
            case "exp": return Math.exp(first);
            case "ln":
            case "log": return Math.log(first);
            case "log10": return Math.log10(first);
            case "sign":
            case "signum": return Math.signum(first);
            case "pow": return Math.pow(first, arguments.get(1).evaluate(t));
            case "clamp": {
                double low = arguments.get(1).evaluate(t);
                double high = arguments.get(2).evaluate(t);
                return Math.max(low, Math.min(high, first));
            }
            case "min": {
                double value = first;
                for (int index = 1; index < arguments.size(); index++) {
                    value = Math.min(value, arguments.get(index).evaluate(t));
                }
                return value;
            }
            case "max": {
                double value = first;
                for (int index = 1; index < arguments.size(); index++) {
                    value = Math.max(value, arguments.get(index).evaluate(t));
                }
                return value;
            }
            default: throw new IllegalArgumentException("unknown function " + name);
        }
    }
}
