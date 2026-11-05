package io.github.marcocavedon.provisio.demand.internal;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * {@code business_calendar}: one document per year (ADR 0026).
 *
 * <p>The calendar is read whole for every series, so it is stored in a handful of documents. Closed days are the
 * exception and are listed; every other day from {@code from} to {@code to} is open.
 *
 * <p>Days from {@code plannedFrom} on are planned, not observed (ADR 0029). The field is absent in a year that is
 * observed in full, so documents written before planned days existed read as fully observed, which they are.
 *
 * @param year the {@code _id}
 * @param from first day covered in that year
 * @param to last day covered in that year, included
 * @param plannedFrom first planned day in that year, or {@code null} if the year is observed in full
 * @param closedDays closed days in that range, ordered
 */
@Document(collection = CalendarYearDocument.COLLECTION)
public record CalendarYearDocument(@Id Integer year, LocalDate from, LocalDate to, LocalDate plannedFrom,
    List<LocalDate> closedDays) {

  public static final String COLLECTION = "business_calendar";
}
