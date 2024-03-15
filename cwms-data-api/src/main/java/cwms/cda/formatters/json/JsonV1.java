package cwms.cda.formatters.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import cwms.cda.data.dto.CwmsDTOBase;
import cwms.cda.data.dto.Office;
import cwms.cda.formatters.Formats;
import cwms.cda.formatters.FormattingException;
import cwms.cda.formatters.OfficeFormatV1;
import cwms.cda.formatters.OutputFormatter;
import cwms.cda.formatters.annotations.FormattableWith;
import io.javalin.http.BadRequestResponse;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * A Formatter for the calls that returned JSON generated by CWMS itself inside of Oracle.
 */
public class JsonV1 implements OutputFormatter {

    private final ObjectMapper om;

    public JsonV1() {
        this(new ObjectMapper());
    }

    public JsonV1(ObjectMapper om) {
        this.om = om.copy();
        this.om.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
        this.om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.om.registerModule(new JavaTimeModule());
    }

    @NotNull
    public static ObjectMapper buildObjectMapper() {
        return buildObjectMapper(new ObjectMapper());
    }

    @NotNull
    public static ObjectMapper buildObjectMapper(ObjectMapper om) {
        ObjectMapper retVal = om.copy();

        retVal.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
        retVal.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        retVal.registerModule(new JavaTimeModule());
        return retVal;
    }

    @Override
    public String getContentType() {
        return Formats.JSON;
    }

    @Override
    public String format(CwmsDTOBase dto) {
        Object fmtv1 = buildFormatting(dto);
        try {
            return om.writeValueAsString(fmtv1);
        } catch (JsonProcessingException e) {
            throw new FormattingException("Could not format:" + dto, e);
        }
    }

    @Override
    public String format(List<? extends CwmsDTOBase> dtoList) {
        Object wrapped = buildFormatting(dtoList);
        try {
            return om.writeValueAsString(wrapped);
        } catch (JsonProcessingException e) {
            throw new FormattingException("Could not format list:" + dtoList, e);
        }
    }

    private Object buildFormatting(CwmsDTOBase dto) {
        Object retVal = null;

        if (dto instanceof Office) {
            List<Office> offices = Arrays.asList((Office) dto);
            retVal = new OfficeFormatV1(offices);
        } else if (dto != null && isFormattableWith(dto.getClass())) {
            // Any types that have to be handle as special cases
            // should be in else if branches before this
            // If the class is in the annotation assume we can just return it.
            retVal = dto;
        }

        if (retVal == null) {
            String klassName = "unknown";
            if (dto != null) {
                klassName = dto.getClass().getName();
            }
            throw new BadRequestResponse(
                    String.format("Format %s not implemented for data of class:%s",
							getContentType(), klassName));
        }
        return retVal;
    }

    private boolean isFormattableWith(Class<?> klass) {
        FormattableWith[] formats = klass.getAnnotationsByType(FormattableWith.class);
        for (FormattableWith format : formats) {
            /**
             * Compare against the actual formatter not the name
             */
            if (format.formatter().equals(JsonV1.class)) {
                return true;
            }
        }
        return false;
    }

    private Object buildFormatting(List<? extends CwmsDTOBase> daoList) {
        Object retVal = null;

        if (daoList != null && !daoList.isEmpty()) {
            CwmsDTOBase firstObj = daoList.get(0);
            if (firstObj instanceof Office) {
                List<Office> officesList = daoList.stream()
                        .map(Office.class::cast)
                        .collect(Collectors.toList());
                retVal = new OfficeFormatV1(officesList);
            } else if (firstObj != null && isFormattableWith(firstObj.getClass())) {
                // If dataType annotated with the class we can return an array of them.
                // If a class needs to be handled differently an else_if branch can be added above
                // here and a wrapper obj used to format the return value however is desired.
                retVal = daoList;
            }

            if (retVal == null) {
                String klassName = "unknown";
                if (firstObj != null) {
                    klassName = firstObj.getClass().getName();
                }
                throw new BadRequestResponse(String.format("Format %s not implemented for data of"
						+ " class:%s", getContentType(), klassName));
            }
        } else if (daoList != null) {
            // If the list is empty we can just return an empty array.
            retVal = new Object[0];
        }
        return retVal;
    }

}
