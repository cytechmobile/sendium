package gr.cytech.sendium.core.http;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Restricts authentication to the native API, without changing Kannel or other endpoints. */
@NameBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface NativeApiAuthenticated {
}
