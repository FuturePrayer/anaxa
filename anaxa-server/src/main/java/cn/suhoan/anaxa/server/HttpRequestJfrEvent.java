package cn.suhoan.anaxa.server;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("cn.suhoan.anaxa.HttpRequest")
@Label("Anaxa HTTP Request")
@Category({"AnaxaDB", "HTTP"})
final class HttpRequestJfrEvent extends Event {
    @Label("Trace Id")
    String traceId;

    @Label("Principal")
    String principal;

    @Label("Method")
    String method;

    @Label("Route")
    String route;

    @Label("Status")
    int statusCode;

    @Label("Remote Address")
    String remoteAddress;

    @Label("Duration Millis")
    long durationMillis;
}
