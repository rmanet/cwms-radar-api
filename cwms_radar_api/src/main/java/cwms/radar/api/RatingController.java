package cwms.radar.api;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletResponse;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import static com.codahale.metrics.MetricRegistry.*;
import com.codahale.metrics.Timer;

import cwms.radar.api.errors.RadarError;
import cwms.radar.data.CwmsDataManager;
import cwms.radar.formatters.Formats;
import io.javalin.apibuilder.CrudHandler;
import io.javalin.http.Context;
import io.javalin.plugin.openapi.annotations.OpenApi;
import io.javalin.plugin.openapi.annotations.OpenApiParam;
import io.javalin.plugin.openapi.annotations.OpenApiResponse;


public class RatingController implements CrudHandler {
    private static final Logger logger = Logger.getLogger(RatingController.class.getName());
    private final MetricRegistry metrics;// = new MetricRegistry();
    private final Meter getAllRequests;// = metrics.meter(OfficeController.class.getName()+"."+"getAll.count");
    private final Timer getAllRequestsTime;// =metrics.timer(OfficeController.class.getName()+"."+"getAll.time");
    private final Meter getOneRequest;
    private final Timer getOneRequestTime;
    private final Histogram requestResultSize;

    public RatingController(MetricRegistry metrics){
        this.metrics=metrics;
        String className = this.getClass().getName();
        getAllRequests = this.metrics.meter(name(className,"getAll","count"));
        getAllRequestsTime = this.metrics.timer(name(className,"getAll","time"));
        getOneRequest = this.metrics.meter(name(className,"getOne","count"));
        getOneRequestTime = this.metrics.timer(name(className,"getOne","time"));
        requestResultSize = this.metrics.histogram((name(className,"results","size")));
    }

    @OpenApi(ignore = true)
    @Override
    public void create(Context ctx) {
        ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED);
    }

    @OpenApi(ignore = true)
    @Override
    public void delete(Context ctx, String rating) {
        ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED);
    }

    @OpenApi(
        queryParams = {
            @OpenApiParam(name="name", required=false, description="Specifies the name(s) of the time series whose data is to be included in the response. A case insensitive comparison is used to match names."),
            @OpenApiParam(name="office", required=false, description="Specifies the owning office of the location level(s) whose data is to be included in the response. If this field is not specified, matching location level information from all offices shall be returned."),
            @OpenApiParam(name="unit", required=false, description="Specifies the unit or unit system of the response. Valid values for the unit field are:\r\n 1. EN.   Specifies English unit system.  Location level values will be in the default English units for their parameters.\r\n2. SI.   Specifies the SI unit system.  Location level values will be in the default SI units for their parameters.\r\n3. Other. Any unit returned in the response to the units URI request that is appropriate for the requested parameters."),
            @OpenApiParam(name="datum", required=false, description="Specifies the elevation datum of the response. This field affects only elevation location levels. Valid values for this field are:\r\n1. NAVD88.  The elevation values will in the specified or default units above the NAVD-88 datum.\r\n2. NGVD29.  The elevation values will be in the specified or default units above the NGVD-29 datum."),
            @OpenApiParam(name="at", required=false, description="Specifies the start of the time window for data to be included in the response. If this field is not specified, any required time window begins 24 hours prior to the specified or default end time."),
            @OpenApiParam(name="end", required=false, description="Specifies the end of the time window for data to be included in the response. If this field is not specified, any required time window ends at the current time"),
            @OpenApiParam(name="timezone", required=false, description="Specifies the time zone of the values of the begin and end fields (unless otherwise specified), as well as the time zone of any times in the response. If this field is not specified, the default time zone of UTC shall be used."),
            @OpenApiParam(name="format", required=false, description="Specifies the encoding format of the response. Valid values for the format field for this URI are:\r\n1.    tab\r\n2.    csv\r\n3.    xml\r\n4.    json (default)")
        },
        responses = {
            @OpenApiResponse(status="200" ),
            @OpenApiResponse(status="404", description = "The provided combination of parameters did not find a rating table."),
            @OpenApiResponse(status="501", description = "Requested format is not implemented")

        },
        tags = {"Ratings"}
    )
    @Override
    public void getAll(Context ctx) {
        getAllRequests.mark();
        try (
            final Timer.Context time_context = getAllRequestsTime.time();
                CwmsDataManager cdm = new CwmsDataManager(ctx);
            ) {
                String format = ctx.queryParamAsClass("format",String.class).getOrDefault("json");
                String names = ctx.queryParam("names");
                String unit = ctx.queryParam("unit");
                String datum = ctx.queryParam("datum");
                String office = ctx.queryParam("office");
                String start = ctx.queryParam("at");
                String end = ctx.queryParam("end");
                String timezone = ctx.queryParamAsClass("timezone",String.class).getOrDefault("UTC");
                String size = ctx.queryParam("size");

                switch(format){
                    case "json": {ctx.contentType(Formats.JSON); break;}
                    case "tab": {ctx.contentType(Formats.TAB); break;}
                    case "csv": {ctx.contentType(Formats.CSV); break;}
                    case "xml": {ctx.contentType(Formats.XML); break;}
                    case "wml2": {ctx.contentType(Formats.WML2); break;}
                    case "jpg": // same handler
                    case "png":
                     default: {
                        ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED).json(RadarError.notImplemented());
                        return;
                     }
                }

                String results = cdm.getRatings(names,format,unit,datum,office,start,end,timezone,size);
                ctx.status(HttpServletResponse.SC_OK);
                ctx.result(results);
                requestResultSize.update(results.length());
        } catch (SQLException ex) {
            RadarError re = new RadarError("Failed to process request");
            logger.log(Level.SEVERE, re.toString(), ex);
            ctx.status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            ctx.json(re);
        }

    }

    @OpenApi(ignore = true)
    @Override
    public void getOne(Context ctx, String rating) {
        getOneRequest.mark();
        try (
            final Timer.Context time_context = getOneRequestTime.time();
            ){
                ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED).json(RadarError.notImplemented());
        }
    }

    @OpenApi(ignore = true)
    @Override
    public void update(Context ctx, String rating) {
        ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED).json(RadarError.notImplemented());
    }

}
