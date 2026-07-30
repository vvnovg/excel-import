package io.github.excelimport.internal.write;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;

/**
 * Прокси над {@link Connection}, передаваемым в {@code BatchValidator}: запрещает
 * управление транзакцией и закрытие, потому что этим владеет {@link BatchProcessor}.
 */
public final class GuardedConnection {

    private static final Set<String> FORBIDDEN = Set.of(
            "commit", "rollback", "close", "setAutoCommit", "abort", "setSavepoint",
            "releaseSavepoint", "setTransactionIsolation");

    private GuardedConnection() {}

    public static Connection wrap(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new Handler(delegate));
    }

    private record Handler(Connection delegate) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (FORBIDDEN.contains(method.getName())) {
                throw new IllegalStateException(
                        "BatchValidator не должен вызывать " + method.getName()
                                + "() — транзакцией управляет библиотека");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }
}
