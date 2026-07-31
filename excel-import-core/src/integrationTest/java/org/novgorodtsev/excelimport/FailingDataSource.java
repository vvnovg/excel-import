package org.novgorodtsev.excelimport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** DataSource, который перестаёт выдавать соединения после N успешных выдач. */
final class FailingDataSource {

    private FailingDataSource() {}

    static DataSource failAfter(DataSource delegate, int successfulConnections) {
        AtomicInteger issued = new AtomicInteger();
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getConnection".equals(method.getName())
                    && issued.getAndIncrement() >= successfulConnections) {
                throw new SQLException("имитация обрыва соединения", "08006");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        };
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, handler);
    }
}
