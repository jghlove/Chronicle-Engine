package net.openhft.chronicle.engine2.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Stream;

/**
 * Created by peter on 22/05/15.
 */
public interface Asset extends Permissoned, Subscription {
    String name();

    @Nullable
    Asset parent();

    @NotNull
    Stream<Asset> children();

    @NotNull
    Asset acquireChild(String name) throws AssetNotFoundException;

    @Nullable
    Asset getChild(String name);

    void removeChild(String name);

    boolean isReadOnly();

    <V> V acquireView(Class<V> vClass, String queryString);

    <V> V acquireView(Class<V> vClass, Class class1, String queryString);

    <V> V acquireView(Class<V> vClass, Class class1, Class class2, String queryString);

    <I extends Interceptor> I acquireInterceptor(Class<I> iClass);

    Asset add(String name, Assetted resource);
}
