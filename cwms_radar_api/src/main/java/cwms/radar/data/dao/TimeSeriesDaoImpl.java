package cwms.radar.data.dao;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import cwms.radar.data.dto.Catalog;
import cwms.radar.data.dto.CwmsDTOPaginated;
import cwms.radar.data.dto.RecentValue;
import cwms.radar.data.dto.TimeSeries;
import cwms.radar.data.dto.Tsv;
import cwms.radar.data.dto.TsvDqu;
import cwms.radar.data.dto.TsvDquId;
import cwms.radar.data.dto.TsvId;
import cwms.radar.data.dto.catalog.CatalogEntry;
import cwms.radar.data.dto.catalog.TimeseriesCatalogEntry;
import org.jetbrains.annotations.NotNull;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Record3;
import org.jooq.Record5;
import org.jooq.Result;
import org.jooq.SQL;
import org.jooq.SelectConditionStep;
import org.jooq.SelectHavingStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSelectStep;
import org.jooq.conf.ParamType;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

import usace.cwms.db.dao.ifc.ts.CwmsDbTs;
import usace.cwms.db.dao.util.services.CwmsDbServiceLookup;
import usace.cwms.db.jooq.codegen.packages.CWMS_ROUNDING_PACKAGE;
import usace.cwms.db.jooq.codegen.packages.CWMS_TS_PACKAGE;
import usace.cwms.db.jooq.codegen.packages.CWMS_UTIL_PACKAGE;
import usace.cwms.db.jooq.codegen.tables.AV_CWMS_TS_ID2;
import usace.cwms.db.jooq.codegen.tables.AV_TSV;
import usace.cwms.db.jooq.codegen.tables.AV_TSV_DQU;
import usace.cwms.db.jooq.codegen.tables.AV_TS_GRP_ASSGN;

import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.partitionBy;
import static usace.cwms.db.jooq.codegen.tables.AV_CWMS_TS_ID2.AV_CWMS_TS_ID2;

public class TimeSeriesDaoImpl extends JooqDao<TimeSeries> implements TimeSeriesDao
{
	private static final Logger logger = Logger.getLogger(TimeSeriesDaoImpl.class.getName());

	public static final boolean OVERRIDE_PROTECTION = true;

	public TimeSeriesDaoImpl(DSLContext dsl)
	{
		super(dsl);
	}

	public String getTimeseries(String format, String names, String office, String units, String datum, String begin,
								String end, String timezone) {
		return CWMS_TS_PACKAGE.call_RETRIEVE_TIME_SERIES_F(dsl.configuration(),
				names, format, units,datum, begin, end, timezone, office);
	}

	public ZonedDateTime asZonedDateTime(String timeStr, ZonedDateTime fallback, String timezone){
		if(timeStr == null){
			timeStr = fallback.toLocalDateTime().toString();
		}

		ZoneId zone = timezone == null ? ZoneOffset.UTC.normalized() : ZoneId.of(timezone);

		// Parse the date time in the best format it can find. Timezone is optional, but use it if it's found.
		TemporalAccessor beginParsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(timeStr, ZonedDateTime::from, LocalDateTime::from);

		ZonedDateTime beginTime;
		if(beginParsed instanceof ZonedDateTime)
		{
			beginTime = ZonedDateTime.from(beginParsed);
		}
		else
		{
			beginTime = LocalDateTime.from(beginParsed).atZone(zone);
		}

		return beginTime;

	}

	public TimeSeries getTimeseries(String page, int pageSize, String names, String office, String units, String datum, String begin, String end, String timezone) {

		if(begin == null)
		{
			begin = ZonedDateTime.now().minusDays(1).toLocalDateTime().toString();
		}
		if(end == null)
		{
			end = ZonedDateTime.now().toLocalDateTime().toString();
		}


		ZoneId zone = timezone == null ? ZoneOffset.UTC.normalized() : ZoneId.of(timezone);

		// Parse the date time in the best format it can find. Timezone is optional, but use it if it's found.
		TemporalAccessor beginParsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(begin, ZonedDateTime::from, LocalDateTime::from);
		TemporalAccessor endParsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(end, ZonedDateTime::from, LocalDateTime::from);

		ZonedDateTime beginTime = beginParsed instanceof ZonedDateTime ? ZonedDateTime.from(beginParsed) : LocalDateTime.from(beginParsed).atZone(zone);
		// If the end time doesn't have a timezone, but begin did, use begin's timezone as end's.
		ZonedDateTime endTime = endParsed instanceof ZonedDateTime ? ZonedDateTime.from(endParsed) : LocalDateTime.from(endParsed).atZone(beginTime.getZone());

		if(timezone == null) {
			if(beginTime.getZone().equals(beginTime.getOffset()))
				throw new IllegalArgumentException("Time cannot contain only an offset without the timezone.");
			// If no timezone was found, get it from begin_time
			zone = beginTime.getZone();
		}

		String cursor = null;
		Timestamp tsCursor = null;
		Integer total = null;

		if(page != null && !page.isEmpty())
		{
			String[] parts = CwmsDTOPaginated.decodeCursor(page);

			logger.fine("Decoded cursor");
			for( String p: parts){
				logger.finest(p);
			}

			if(parts.length > 1)
			{
				cursor = parts[0];
				tsCursor = Timestamp.from(Instant.ofEpochMilli(Long.parseLong(parts[0])));

				if(parts.length > 2)
					total = Integer.parseInt(parts[1]);

				// Use the pageSize from the original cursor, for consistent paging
				pageSize = Integer.parseInt(parts[parts.length - 1]);   // Last item is pageSize
			}
		}

		final String recordCursor = cursor;
		final int recordPageSize = pageSize;

		Field<String> officeId = CWMS_UTIL_PACKAGE.call_GET_DB_OFFICE_ID(office != null ? DSL.val(office) : CWMS_UTIL_PACKAGE.call_USER_OFFICE_ID());
		Field<String> tsId = CWMS_TS_PACKAGE.call_GET_TS_ID__2(DSL.val(names), officeId);
		Field<BigDecimal> tsCode = CWMS_TS_PACKAGE.call_GET_TS_CODE__2(tsId, officeId);
		Field<String> unit = units.compareToIgnoreCase("SI") == 0 || units.compareToIgnoreCase("EN") == 0 ?
				CWMS_UTIL_PACKAGE.call_GET_DEFAULT_UNITS(CWMS_TS_PACKAGE.call_GET_BASE_PARAMETER_ID(tsCode), DSL.val(units, String.class)) :
				DSL.val(units, String.class);

		// This code assumes the database timezone is in UTC (per Oracle recommendation)
		// Wrap in table() so JOOQ can parse the result
		@SuppressWarnings("deprecated") SQL retrieveTable = DSL.sql("table(" + CWMS_TS_PACKAGE.call_RETRIEVE_TS_OUT_TAB(
				tsId,
				unit,
				CWMS_UTIL_PACKAGE.call_TO_TIMESTAMP__2(DSL.val(beginTime.toInstant().toEpochMilli())),
				CWMS_UTIL_PACKAGE.call_TO_TIMESTAMP__2(DSL.val(endTime.toInstant().toEpochMilli())),
				DSL.inline("UTC", String.class),    // All times are sent as UTC to the database, regardless of requested timezone.
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				officeId) + ")"
		);

		SelectSelectStep<Record5<String,String,String,BigDecimal,Integer>> metadataQuery = dsl.select(
				tsId.as("NAME"),
				officeId.as("OFFICE_ID"),
				unit.as("UNITS"),
				CWMS_TS_PACKAGE.call_GET_INTERVAL(tsId).as("INTERVAL"),
				// If we don't know the total, fetch it from the database (only for first fetch).
				// Total is only an estimate, as it can change if fetching current data, or the timeseries otherwise changes between queries.
				total != null ? DSL.val(total).as("TOTAL") : DSL.selectCount().from(retrieveTable).asField("TOTAL")
		);

		logger.finest( () -> metadataQuery.getSQL(ParamType.INLINED));

		TimeSeries timeseries = metadataQuery.fetchOne(tsMetadata ->
				new TimeSeries(recordCursor,
						recordPageSize,
						tsMetadata.getValue("TOTAL", Integer.class),
						tsMetadata.getValue("NAME", String.class),
						tsMetadata.getValue("OFFICE_ID", String.class),
						beginTime,
						endTime,
						tsMetadata.getValue("UNITS", String.class),
						Duration.ofMinutes(tsMetadata.get("INTERVAL") == null ? 0 : tsMetadata.getValue("INTERVAL", Long.class)))
		);

		if(pageSize != 0) {
			SelectConditionStep<Record3<Timestamp, Double, BigDecimal>> query = dsl.select(
					DSL.field("DATE_TIME", Timestamp.class).as("DATE_TIME"),
					CWMS_ROUNDING_PACKAGE.call_ROUND_DD_F(DSL.field("VALUE", Double.class), DSL.inline("5567899996"), DSL.inline('T')).as("VALUE"),
					CWMS_TS_PACKAGE.call_NORMALIZE_QUALITY(DSL.nvl(DSL.field("QUALITY_CODE", Integer.class), DSL.inline(5))).as("QUALITY_CODE")
			)
					.from(retrieveTable)
					.where(DSL.field("DATE_TIME", Timestamp.class)
							.greaterOrEqual(CWMS_UTIL_PACKAGE.call_TO_TIMESTAMP__2(
									DSL.nvl(DSL.val(tsCursor == null ? null : tsCursor.toInstant().toEpochMilli()),
											DSL.val(beginTime.toInstant().toEpochMilli())))))
					.and(DSL.field("DATE_TIME", Timestamp.class)
							.lessOrEqual(CWMS_UTIL_PACKAGE.call_TO_TIMESTAMP__2(DSL.val(endTime.toInstant().toEpochMilli())))
					);

			if(pageSize > 0)
				query.limit(DSL.val(pageSize + 1));

			logger.finest( () -> query.getSQL(ParamType.INLINED));

			query.fetchInto(tsRecord -> timeseries.addValue(
					tsRecord.getValue("DATE_TIME", Timestamp.class),
					tsRecord.getValue("VALUE", Double.class),
					tsRecord.getValue("QUALITY_CODE", Integer.class)
			)
			);
		}
		return timeseries;
	}

	public Catalog getTimeSeriesCatalog(String page, int pageSize, Optional<String> office){
		int total = 0;
		String tsCursor = "*";
		if( page == null || page.isEmpty() ){
			SelectJoinStep<Record1<Integer>> count = dsl.select(count(asterisk())).from(AV_CWMS_TS_ID2);
			if( office.isPresent() ){
				count.where(AV_CWMS_TS_ID2.DB_OFFICE_ID.eq(office.get()));
			}
			total = count.fetchOne().value1();
		} else {
			logger.fine("getting non-default page");
			// get totally from page
			String[] parts = CwmsDTOPaginated.decodeCursor(page, "|||");

			logger.fine("decoded cursor: " + String.join("|||", parts));
			for( String p: parts){
				logger.finest(p);
			}

			if(parts.length > 1) {
				tsCursor = parts[0].split("/")[1];
				total = Integer.parseInt(parts[1]);
			}
		}

		SelectJoinStep<Record3<String, String, String>> query = dsl.select(
				AV_CWMS_TS_ID2.DB_OFFICE_ID,
				AV_CWMS_TS_ID2.CWMS_TS_ID,
				AV_CWMS_TS_ID2.UNIT_ID
		)
				.from(AV_CWMS_TS_ID2);

		if( office.isPresent() ){
			query.where(AV_CWMS_TS_ID2.DB_OFFICE_ID.upper().eq(office.get().toUpperCase()))
					.and(AV_CWMS_TS_ID2.CWMS_TS_ID.upper().greaterThan(tsCursor));
		} else {
			query.where(AV_CWMS_TS_ID2.CWMS_TS_ID.upper().gt(tsCursor));
		}
		query.orderBy(AV_CWMS_TS_ID2.CWMS_TS_ID).limit(pageSize);
		logger.finest( () -> query.getSQL(ParamType.INLINED));
		Result<Record3<String, String, String>> result = query.fetch();
		List<? extends CatalogEntry> entries = result.stream()
				//.map( e -> e.into(usace.cwms.db.jooq.codegen.tables.records.AV_CWMS_TIMESERIES_ID2) )
				.map( e -> new TimeseriesCatalogEntry(e.get(AV_CWMS_TS_ID2.DB_OFFICE_ID),
						e.get(AV_CWMS_TS_ID2.CWMS_TS_ID),
						e.get(AV_CWMS_TS_ID2.UNIT_ID) )
				)
				.collect(Collectors.toList());
		return new Catalog(tsCursor,total,pageSize,entries);
	}


	// Finds the single most recent TsvDqu within the time window.
	public TsvDqu findMostRecent(String tOfficeId, String tsId, String unit, Timestamp twoWeeksFromNow, Timestamp twoWeeksAgo)
	{
		TsvDqu retval = null;

		AV_TSV_DQU view = AV_TSV_DQU.AV_TSV_DQU;

		Condition nestedCondition = view.ALIASED_ITEM.isNull()
				.and(view.VALUE.isNotNull())
				.and(view.CWMS_TS_ID.eq(tsId))
				.and(view.OFFICE_ID.eq(tOfficeId));

		if(twoWeeksFromNow != null){
			nestedCondition = nestedCondition.and(view.DATE_TIME.lt(twoWeeksFromNow));
		}

		// Is this really optional?
		if(twoWeeksAgo != null){
			nestedCondition = nestedCondition.and(view.DATE_TIME.gt(twoWeeksAgo));
		}

		String maxFieldName = "MAX_DATE_TIME";
		SelectHavingStep<Record1<Timestamp>> select = dsl.select(max(view.DATE_TIME).as(maxFieldName)).from(
				view).where(nestedCondition).groupBy(view.TS_CODE);

		Record dquRecord = dsl.select(asterisk()).from(view).where(view.DATE_TIME.in(select)).and(
				view.CWMS_TS_ID.eq(tsId)).and(view.OFFICE_ID.eq(tOfficeId)).and(view.UNIT_ID.eq(unit)).and(
				view.VALUE.isNotNull()).and(view.ALIASED_ITEM.isNull()).fetchOne();

		if(dquRecord != null)
		{
			retval = dquRecord.map(r -> {
				usace.cwms.db.jooq.codegen.tables.records.AV_TSV_DQU dqu = r.into(view);
				TsvDqu tsv = null;
				if(r != null)
				{
					TsvDquId id = new TsvDquId(dqu.getOFFICE_ID(), dqu.getTS_CODE(), dqu.getUNIT_ID(), dqu.getDATE_TIME());
					tsv = new TsvDqu(id, dqu.getCWMS_TS_ID(), dqu.getVERSION_DATE(), dqu.getDATA_ENTRY_DATE(), dqu.getVALUE(), dqu.getQUALITY_CODE(), dqu.getSTART_DATE(), dqu.getEND_DATE());
				}
				return tsv;
			});
		}

		return retval;
	}


	// This is similar to the code used for sparklines...
	// Finds all the Tsv data points in the time range for all the specified tsIds.
	public List<Tsv> findInDateRange(Collection<String> tsIds, Date startDate, Date endDate) {
		List<Tsv> retval = Collections.emptyList();

		if (tsIds != null && !tsIds.isEmpty()) {

			Timestamp start = new Timestamp(startDate.getTime());
			Timestamp end = new Timestamp(endDate.getTime());

			AV_TSV tsvView = AV_TSV.AV_TSV;
			usace.cwms.db.jooq.codegen.tables.AV_CWMS_TS_ID2 tsView = AV_CWMS_TS_ID2;
			retval = dsl.select(tsvView.asterisk(), tsView.CWMS_TS_ID)
					.from(tsvView.join(tsView).on(tsvView.TS_CODE.eq(tsView.TS_CODE.cast(Long.class))))
					.where(
							tsView.CWMS_TS_ID.in(tsIds).and(tsvView.DATE_TIME.ge(start)).and(tsvView.DATE_TIME.lt(end)).and(
									tsvView.START_DATE.le(end)).and(tsvView.END_DATE.gt(start))).orderBy(tsvView.DATE_TIME).fetch(
							jrecord -> buildTsvFromViewRow(jrecord.into(tsvView)));
		}
		return retval;
	}

	@NotNull
	private Tsv buildTsvFromViewRow(usace.cwms.db.jooq.codegen.tables.records.AV_TSV into)
	{
		TsvId id = new TsvId(into.getTS_CODE(), into.getDATE_TIME(), into.getVERSION_DATE(), into.getDATA_ENTRY_DATE());

		return new Tsv(id, into.getVALUE(), into.getQUALITY_CODE(), into.getSTART_DATE(), into.getEND_DATE());
	}

	// Finds single most recent value within the window for each of the tsCodes
	public List<RecentValue> findMostRecentsInRange(List<String> tsIds, Timestamp pastdate, Timestamp futuredate) {
		final List<RecentValue> retval = new ArrayList<>();

		if (tsIds != null && !tsIds.isEmpty()) {
			AV_TSV_DQU tsvView = AV_TSV_DQU.AV_TSV_DQU;
			AV_CWMS_TS_ID2 tsView = AV_CWMS_TS_ID2;
			SelectConditionStep<Record> innerSelect
					= dsl.select(tsvView.asterisk(),
					max(tsvView.DATE_TIME).over(partitionBy(tsvView.TS_CODE)).as("max_date_time"), tsView.CWMS_TS_ID)
					.from(tsvView.join(tsView).on(tsvView.TS_CODE.eq(tsView.TS_CODE.cast(Long.class))))
					.where(
							tsView.CWMS_TS_ID.in(tsIds)
									.and(tsvView.VALUE.isNotNull())
									.and(tsvView.DATE_TIME.lt(futuredate))
									.and(tsvView.DATE_TIME.gt(pastdate))
									.and(tsvView.START_DATE.le(futuredate))
									.and(tsvView.END_DATE.gt(pastdate)));


			Field[] queryFields = new Field[]{
					tsView.CWMS_TS_ID,
					tsvView.OFFICE_ID,
					tsvView.TS_CODE,
					tsvView.UNIT_ID,
					tsvView.DATE_TIME,
					tsvView.VERSION_DATE,
					tsvView.DATA_ENTRY_DATE,
					tsvView.VALUE,
					tsvView.QUALITY_CODE,
					tsvView.START_DATE,
					tsvView.END_DATE,
					};

			// look them back up by name b/c we are using them on results of innerselect.
			List<Field<Object>> fields = Arrays.stream(queryFields)
					.map(Field::getName)
					.map(DSL::field).collect(
							Collectors.toList());

			// I want to select tsvView.asterisk but we are selecting from an inner select and
			// even though the inner select selects tsvView.asterisk it isn't the same.
			// So we will just select the fields we want.  Unfortunately that means our results
			// won't map into AV_TSV.AV_TSV
			dsl.select(fields)
					.from(innerSelect)
					.where(field("DATE_TIME").eq(innerSelect.field("max_date_time")))
					.forEach( jrecord -> {
						RecentValue recentValue = buildRecentValue(tsvView, tsView, jrecord);
						retval.add(recentValue);
					});
		}
		return retval;
	}

	@NotNull
	private RecentValue buildRecentValue(AV_TSV_DQU tsvView, usace.cwms.db.jooq.codegen.tables.AV_CWMS_TS_ID2 tsView,
									   Record jrecord)
	{
		return buildRecentValue(tsvView, jrecord, tsView.CWMS_TS_ID.getName());
	}

	@NotNull
	private RecentValue buildRecentValue(AV_TSV_DQU tsvView, AV_TS_GRP_ASSGN tsView, Record jrecord)
	{
		return buildRecentValue(tsvView, jrecord, tsView.TS_ID.getName());
	}

	@NotNull
	private RecentValue buildRecentValue(AV_TSV_DQU tsvView, Record jrecord, String tsColumnName)
	{
		Timestamp dataEntryDate;
		// TODO:
		// !!! skipping DATA_ENTRY_DATE for now.  Need to figure out how to fix mapping in jooq.
		//	!! dataEntryDate= jrecord.getValue("data_entry_date", Timestamp.class); // maps to oracle.sql.TIMESTAMP
		// !!!
		dataEntryDate = null;
		// !!!

		TsvDqu tsv = buildTsvDqu(tsvView, jrecord, dataEntryDate);
		String tsId = jrecord.getValue(tsColumnName, String.class);
		return new RecentValue(tsId, tsv);
	}

	@NotNull
	private TsvDqu buildTsvDqu(AV_TSV_DQU tsvView, Record jrecord, Timestamp dataEntryDate)
	{
		TsvDquId id = buildDquId(tsvView, jrecord);

		return new TsvDqu(id, jrecord.getValue(tsvView.CWMS_TS_ID.getName(), String.class),
				jrecord.getValue(tsvView.VERSION_DATE.getName(), Timestamp.class), dataEntryDate,
				jrecord.getValue(tsvView.VALUE.getName(), Double.class),
				jrecord.getValue(tsvView.QUALITY_CODE.getName(), Long.class),
				jrecord.getValue(tsvView.START_DATE.getName(), Timestamp.class),
				jrecord.getValue(tsvView.END_DATE.getName(), Timestamp.class));
	}


	public List<RecentValue> findRecentsInRange(String office, String categoryId, String groupId, Timestamp pastLimit, Timestamp futureLimit)
	{
		List<RecentValue> retval  = new ArrayList<>();

		if (categoryId != null && groupId != null) {
			AV_TSV_DQU tsvView = AV_TSV_DQU.AV_TSV_DQU;  // should we look at the daterange and possible use 30D view?

			AV_TS_GRP_ASSGN tsView = AV_TS_GRP_ASSGN.AV_TS_GRP_ASSGN;

			SelectConditionStep<Record> innerSelect
					= dsl.select(tsvView.asterisk(), tsView.TS_ID, tsView.ATTRIBUTE,
					max(tsvView.DATE_TIME).over(partitionBy(tsvView.TS_CODE)).as("max_date_time"), tsView.TS_ID)
					.from(tsvView.join(tsView).on(tsvView.TS_CODE.eq(tsView.TS_CODE.cast(Long.class))))
					.where(
							tsView.DB_OFFICE_ID.eq(office)
									.and(tsView.CATEGORY_ID.eq(categoryId))
									.and(tsView.GROUP_ID.eq(groupId))
									.and(tsvView.VALUE.isNotNull())
									.and(tsvView.DATE_TIME.lt(futureLimit))
									.and(tsvView.DATE_TIME.gt(pastLimit))
									.and(tsvView.START_DATE.le(futureLimit))
									.and(tsvView.END_DATE.gt(pastLimit)));

			Field[] queryFields = new Field[]{
					tsvView.OFFICE_ID,
					tsvView.TS_CODE,
					tsvView.DATE_TIME,
					tsvView.VERSION_DATE,
					tsvView.DATA_ENTRY_DATE,
					tsvView.VALUE,
					tsvView.QUALITY_CODE,
					tsvView.START_DATE,
					tsvView.END_DATE,
					tsvView.UNIT_ID,
					tsView.TS_ID, tsView.ATTRIBUTE};

			List<Field<Object>> fields = Arrays.stream(queryFields)
					.map(Field::getName)
					.map(DSL::field).collect(
					Collectors.toList());


			// I want to select tsvView.asterisk but we are selecting from an inner select and
			// even though the inner select selects tsvView.asterisk it isn't the same.
			// So we will just select the fields we want.
			// Unfortunately that means our results won't map into AV_TSV.AV_TSV
			dsl.select(fields)
					.from(innerSelect)
					.where(field(tsvView.DATE_TIME.getName()).eq(innerSelect.field("max_date_time")))
					.orderBy(field(tsView.ATTRIBUTE.getName()))
					.forEach( jrecord -> {
						RecentValue recentValue = buildRecentValue(tsvView, tsView, jrecord);
						retval.add(recentValue);
					});
		}
		return retval;
	}

	@NotNull
	private TsvDquId buildDquId(AV_TSV_DQU tsvView, Record jrecord)
	{
		return new TsvDquId(jrecord.getValue(tsvView.OFFICE_ID.getName(), String.class),
				jrecord.getValue(tsvView.TS_CODE.getName(), Long.class),
				jrecord.getValue(tsvView.UNIT_ID.getName(), String.class),
				jrecord.getValue(tsvView.DATE_TIME.getName(), Timestamp.class));
	}

	public void create(TimeSeries input)
	{
		dsl.connection(connection -> {
			CwmsDbTs tsDao = CwmsDbServiceLookup.buildCwmsDb(CwmsDbTs.class, connection);

			int utcOffsetMinutes = 0;
			int intervalForward = 0;
			int intervalBackward = 0;
			boolean versionedFlag = false;
			boolean activeFlag = true;
			BigInteger tsCode = tsDao.createTsCodeBigInteger(connection, input.getOfficeId(), input.getName(),
					utcOffsetMinutes, intervalForward, intervalBackward, versionedFlag, activeFlag);
		});
	}

	public void store(TimeSeries input, Timestamp versionDate)
	{
		dsl.connection(connection -> {
			store(connection, input.getOfficeId(), input.getName(), input.getUnits(), versionDate, input.getValues());
		});
	}

	public void update(TimeSeries input) throws SQLException
	{
		String name = input.getName();
		if(!timeseriesExists(name)){
			throw new SQLException("Cannot update a non-existant Timeseries. Create " + name + " first.");
		}
		dsl.connection(connection -> {
			store(connection, input.getOfficeId(), name, input.getUnits(), NON_VERSIONED, input.getValues());
		});
	}

	public void store(Connection connection, String officeId, String tsId, String units, Timestamp versionDate,
						   List<TimeSeries.Record> values) throws SQLException
	{
		CwmsDbTs tsDao =  CwmsDbServiceLookup.buildCwmsDb(CwmsDbTs.class, connection);

		final int count = values == null ? 0 : values.size();

		final long[] timeArray = new long[count];
		final double[] valueArray = new double[count];
		final int[] qualityArray = new int[count];

		if(values != null && !values.isEmpty())
		{
			Iterator<TimeSeries.Record> iter = values.iterator();
			for(int i = 0; iter.hasNext(); i++)
			{
				TimeSeries.Record value = iter.next();
				timeArray[i] = value.getDateTime().getTime();
				valueArray[i] = value.getValue();
				qualityArray[i] = value.getQualityCode();
			}
		}

		final boolean createAsLrts = false;
		StoreRule storeRule = StoreRule.DELETE_INSERT;

		long completedAt = tsDao.store(connection, officeId, tsId, units, timeArray, valueArray, qualityArray, count,
				storeRule.getRule(), OVERRIDE_PROTECTION, versionDate, createAsLrts);
	}

	public void delete(String officeId, String tsId)
	{
		dsl.connection(connection -> {
			CwmsDbTs tsDao = CwmsDbServiceLookup.buildCwmsDb(CwmsDbTs.class, connection);

			tsDao.deleteAll(connection, officeId, tsId);
		});
	}

	protected BigDecimal retrieveTsCode(String tsId)
	{

		return dsl.select(AV_CWMS_TS_ID2.AV_CWMS_TS_ID2.TS_CODE).from(
				AV_CWMS_TS_ID2.AV_CWMS_TS_ID2).where(AV_CWMS_TS_ID2.AV_CWMS_TS_ID2.CWMS_TS_ID.eq(tsId))
				.fetchOptional(AV_CWMS_TS_ID2.AV_CWMS_TS_ID2.TS_CODE).orElse(null);
	}

	public boolean timeseriesExists(String tsId)
	{
		return retrieveTsCode(tsId) != null;
	}

}
