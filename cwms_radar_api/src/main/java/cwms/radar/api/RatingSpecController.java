package cwms.radar.api;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletResponse;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import cwms.radar.api.errors.RadarError;
import cwms.radar.data.dao.JooqDao;
import cwms.radar.data.dao.RatingSpecDao;
import cwms.radar.data.dto.rating.RatingSpec;
import cwms.radar.data.dto.rating.RatingSpecs;
import cwms.radar.formatters.ContentType;
import cwms.radar.formatters.Formats;
import io.javalin.apibuilder.CrudHandler;
import io.javalin.core.util.Header;
import io.javalin.http.Context;
import io.javalin.plugin.openapi.annotations.OpenApi;
import io.javalin.plugin.openapi.annotations.OpenApiContent;
import io.javalin.plugin.openapi.annotations.OpenApiParam;
import io.javalin.plugin.openapi.annotations.OpenApiResponse;
import org.jetbrains.annotations.NotNull;
import org.jooq.DSLContext;

import static com.codahale.metrics.MetricRegistry.name;


public class RatingSpecController implements CrudHandler {
    private static final Logger logger = Logger.getLogger(RatingSpecController.class.getName());
    private final MetricRegistry metrics;

    private static final int DEFAULT_PAGE_SIZE = 100;

    private final Histogram requestResultSize;

    public RatingSpecController(MetricRegistry metrics){
        this.metrics=metrics;
        String className = this.getClass().getName();
        requestResultSize = this.metrics.histogram((name(className,"results","size")));
    }

    protected DSLContext getDslContext(Context ctx)
    {
        return JooqDao.getDslContext(ctx);
    }

    private Timer.Context markAndTime(String subject)
    {
        return Controllers.markAndTime(metrics, getClass().getName(), subject);
    }
    
    
    @OpenApi(
        queryParams = {
            @OpenApiParam(name="office", description="Specifies the owning office of the Rating Specs whose data is to be included in the response. If this field is not specified, matching rating information from all offices shall be returned."),
            @OpenApiParam(name="rating-id-mask", description="RegExp that specifies the rating IDs to be included in the response. If this field is not specified, all Rating Specs shall be returned."),
                @OpenApiParam(name="page",
                        description = "This end point can return a lot of data, this identifies where in the request you are. This is an opaque value, and can be obtained from the 'next-page' value in the response."
                ),
                @OpenApiParam(name="page-size", type=Integer.class,
                        description = "How many entries per page returned. Default " + DEFAULT_PAGE_SIZE + "."
                ),
        },
            responses = {
                    @OpenApiResponse(status = "200",
                            content = {
                                    @OpenApiContent(type = Formats.JSON, from = RatingSpecs.class)
                            }

                    )},
        tags = {"Ratings"}
    )
    @Override
    public void getAll(Context ctx)
    {
        String cursor = ctx.queryParamAsClass("page",String.class).getOrDefault("");
        int pageSize = ctx.queryParamAsClass("page-size", Integer.class).getOrDefault(DEFAULT_PAGE_SIZE);

        String office = ctx.queryParam("office");
        String ratingIdMask = ctx.queryParam("rating-id-mask");
        
        String formatHeader = ctx.header(Header.ACCEPT);
        ContentType contentType = Formats.parseHeaderAndQueryParm(formatHeader, "");
        try(final Timer.Context timeContext = markAndTime("getAll"); DSLContext dsl = getDslContext(ctx))
        {
            RatingSpecDao ratingSpecDao = getRatingSpecDao(dsl);
            RatingSpecs ratingSpecs = ratingSpecDao.retrieveRatingSpecs(cursor, pageSize, office, ratingIdMask);

            ctx.contentType(contentType.toString());

            String result = Formats.format(contentType, ratingSpecs);
            ctx.result(result);
            requestResultSize.update(result.length());
            ctx.status(HttpServletResponse.SC_OK);
        }
        catch(Exception ex)
        {
            RadarError re = new RadarError("Failed to process request: " + ex.getLocalizedMessage());
            logger.log(Level.SEVERE, re.toString(), ex);
            ctx.status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR).json(re);
        }


    }

    @OpenApi(
            pathParams = {
                    @OpenApiParam(name = "rating-id", required = true, description = "Specifies the rating-id of the Rating Spec to be included in the response")
            },
            queryParams = {
                    @OpenApiParam(name="office", required=true, description="Specifies the owning office of the Rating Specs whose data is to be included in the response. If this field is not specified, matching rating information from all offices shall be returned."),
            },
            responses = {
                    @OpenApiResponse(status = "200",
                            content = {
                                    @OpenApiContent(isArray = true, from = RatingSpec.class, type = Formats.JSON),
                            }

                    )},
            tags = {"Ratings"}
    )
    @Override
    public void getOne(Context ctx, String ratingId) {
        String formatHeader = ctx.header(Header.ACCEPT);
        ContentType contentType = Formats.parseHeaderAndQueryParm(formatHeader, "");

        String office = ctx.queryParam("office");
        
        try(final Timer.Context timeContext = markAndTime("getOne"); DSLContext dsl = getDslContext(ctx))
        {
            RatingSpecDao ratingSpecDao = getRatingSpecDao(dsl);

            Optional<RatingSpec> template= ratingSpecDao.retrieveRatingSpec(office, ratingId);
            if( template.isPresent() ) {
                String result = Formats.format(contentType, template.get());

                ctx.result(result);
                ctx.contentType(contentType.toString());

                requestResultSize.update(result.length());
                ctx.status(HttpServletResponse.SC_OK);
            } else {
                RadarError re = new RadarError("Unable to find Rating Spec based on parameters given");
                logger.info( () -> new StringBuilder()
                        .append(re).append(System.lineSeparator())
                        .append( "for request ").append( ctx.fullUrl() )
                        .toString());
                ctx.status(HttpServletResponse.SC_NOT_FOUND).json( re );
            }
        }
    }

    @NotNull
    protected RatingSpecDao getRatingSpecDao(DSLContext dsl)
    {
        return new RatingSpecDao(dsl);
    }


    @OpenApi(ignore = true)
    @Override
    public void create(Context ctx)
    {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Specs.
    }

    @OpenApi(ignore = true)
    @Override
    public void update(Context ctx, String locationCode)
    {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Specs.
    }

    @OpenApi(ignore = true)
    @Override
    public void delete(Context ctx, String locationCode)
    {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Specs.
    }
    
}
