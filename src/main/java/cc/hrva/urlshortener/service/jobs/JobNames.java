package cc.hrva.urlshortener.service.jobs;

import java.lang.reflect.Method;

public final class JobNames {

    private JobNames() {
    }

    public static String of(final Method method) {
        return of(method.getDeclaringClass(), method.getName());
    }

    public static String of(final Class<?> declaringClass, final String methodName) {
        return declaringClass.getSimpleName() + "." + methodName;
    }

}
