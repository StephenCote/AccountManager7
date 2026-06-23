package org.cote.accountmanager.iso42001.reporting;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Renders an {@code iso42001.report} (its sections + embedded chart images) to PDF bytes via OpenPDF
 * and stores the result as a {@code data.data} ({@code contentType=application/pdf}), setting
 * {@code report.exportedPdf} (design §4.1–§4.3, §11 Phase 4 / task Phase 5).
 *
 * <p>The certification block is rendered as <b>NOT CERTIFIED</b> — Phase 6 fills the signer/signature
 * fields. Access to the stored PDF via the stream/media servlets is out of scope for this phase.</p>
 *
 * <p><b>⚠ Judgment call (§4.2 layout) — flagged.</b> The design gives a section ordering and a
 * certification-block field list but not exact typography/spacing. Section markdown content is
 * rendered as plain paragraphs (one per line); the RESULTS section embeds the heat-map and
 * effect-size PNGs produced by {@link ChartGenerator} from the section's {@code chartData}. This is a
 * faithful, minimal realization of the §4.2 structure; richer markdown/table rendering is a later
 * refinement.</p>
 */
public class PdfExporter {

	private static final Logger logger = LogManager.getLogger(PdfExporter.class);

	private final BaseRecord user;

	public PdfExporter(BaseRecord user) {
		this.user = user;
	}

	/**
	 * Render the report to PDF bytes — pure, no persistence. The report must carry its {@code sections}
	 * (as returned by {@link ReportGenerator#generate}); chart images are rendered from each section's
	 * {@code chartData}.
	 */
	public byte[] renderPdfBytes(BaseRecord report) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
		try {
			PdfWriter.getInstance(doc, bos);
			doc.open();

			Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
			Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
			Font headFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
			Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

			doc.add(new Paragraph("ISO 42001 AI Management System", titleFont));
			doc.add(new Paragraph("Compliance Report", titleFont));
			doc.add(new Paragraph(" ", metaFont));

			doc.add(new Paragraph("Report ID: " + str(report.get(FieldNames.FIELD_OBJECT_ID)), metaFont));
			doc.add(new Paragraph("Name: " + str(report.get(FieldNames.FIELD_NAME)), metaFont));
			doc.add(new Paragraph("Generated: " + new Date(), metaFont));
			doc.add(new Paragraph("Version: " + str(report.get("reportVersion")), metaFont));
			doc.add(new Paragraph("Report Type: " + str(report.get("reportType")), metaFont));
			doc.add(new Paragraph("Status: " + str(report.get("status")), metaFont));
			doc.add(new Paragraph("Overall Verdict: " + str(report.get("overallVerdict")), metaFont));
			doc.add(new Paragraph("Models Evaluated: " + joinList(report.get("modelsEvaluated")), metaFont));
			doc.add(new Paragraph("Control Areas: " + joinList(report.get("controlAreas")), metaFont));
			doc.add(new Paragraph(" ", metaFont));

			ChartGenerator charts = new ChartGenerator();
			List<BaseRecord> sections = sortedSections(report.get("sections"));
			for (BaseRecord section : sections) {
				String type = str(section.get("sectionType"));
				doc.add(new Paragraph(" ", bodyFont));
				doc.add(new Paragraph(headingLabel(type), headFont));
				String content = section.get("content");
				if (content != null) {
					for (String line : content.split("\n")) {
						/// Blank line → small spacer; otherwise a body paragraph.
						doc.add(new Paragraph(line.isEmpty() ? " " : line, bodyFont));
					}
				}
				String chartData = section.get("chartData");
				if (chartData != null && !chartData.isEmpty()) {
					embedChart(doc, charts.renderHeatMap(chartData, 520, 300), bodyFont);
					embedChart(doc, charts.renderEffectBars(chartData, 520, 300), bodyFont);
				}
			}

			/// Certification block — NOT CERTIFIED until Phase 6 (design §4.2).
			doc.add(new Paragraph(" ", bodyFont));
			doc.add(new Paragraph("Certification", headFont));
			doc.add(new Paragraph("Status: NOT CERTIFIED", bodyFont));
			doc.add(new Paragraph("Report Hash (SHA-256): " + hashHex(report), bodyFont));
			doc.add(new Paragraph("Signature Algorithm: SHA256WithRSA (pending certification)", bodyFont));
			doc.add(new Paragraph("This report has not yet been digitally signed. Certification is "
				+ "performed in the ISO 42001 certification workflow (Phase 6).", bodyFont));

			doc.close();
			return bos.toByteArray();
		} catch (Exception e) {
			logger.error("Failed to render report PDF", e);
			if (doc.isOpen()) {
				try {
					doc.close();
				} catch (Exception ignore) {
					/* already closing on error */
				}
			}
			return null;
		}
	}

	/**
	 * Render and persist the report PDF as a {@code data.data} ({@code contentType=application/pdf}) in
	 * the user's {@code ~/ISO42001Reports} group, then set {@code report.exportedPdf} and update the
	 * report. Returns the created {@code data.data} record (with {@code objectId}), or {@code null} on
	 * failure.
	 */
	public BaseRecord export(BaseRecord report) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		byte[] pdf = renderPdfBytes(report);
		if (pdf == null || pdf.length == 0) {
			logger.error("PDF render produced no bytes; aborting export");
			return null;
		}

		long orgId = lng(report, FieldNames.FIELD_ORGANIZATION_ID);
		long ownerId = lng(report, FieldNames.FIELD_OWNER_ID);
		String reportOid = report.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord createdData;
		try {
			BaseRecord pdfGroup = IOSystem.getActiveContext().getPathUtil()
				.makePath(user, ModelNames.MODEL_GROUP, "~/ISO42001Reports", "DATA", orgId);
			long pdfGroupId = pdfGroup.get(FieldNames.FIELD_ID);

			BaseRecord data = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
			data.set(FieldNames.FIELD_NAME, "report-" + reportOid + ".pdf");
			data.set(FieldNames.FIELD_GROUP_ID, pdfGroupId);
			data.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			data.set(FieldNames.FIELD_OWNER_ID, ownerId);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "application/pdf");
			data.set(FieldNames.FIELD_BYTE_STORE, pdf);
			createdData = ap.create(user, data);
			if (createdData == null) {
				logger.error("PDF data.data CREATE returned null (RBAC?)");
				return null;
			}
		} catch (Exception e) {
			logger.error("Failed to store report PDF as data.data", e);
			return null;
		}

		/// Link report.exportedPdf → the PDF data.data. Re-read the report with a common plan first so
		/// the update carries the group/owner context the update policy needs (sparse patches NPE in
		/// policy resolution — see the Phase-1 model test note).
		try {
			Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_REPORT, FieldNames.FIELD_OBJECT_ID, reportOid);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.planCommon(true);
			BaseRecord toUpdate = ap.find(user, q);
			if (toUpdate == null) {
				logger.error("Could not re-read report " + reportOid + " to set exportedPdf");
				return createdData;
			}
			toUpdate.set("exportedPdf", createdData);
			BaseRecord updated = ap.update(user, toUpdate);
			if (updated == null) {
				logger.warn("report exportedPdf UPDATE returned null for " + reportOid);
			}
		} catch (Exception e) {
			logger.error("Failed to set report.exportedPdf for " + reportOid, e);
		}

		return createdData;
	}

	// ------------------------------------------------------------------

	private static void embedChart(Document doc, byte[] png, Font caption) {
		if (png == null || png.length == 0) {
			return;
		}
		try {
			Image img = Image.getInstance(png);
			img.scaleToFit(500, 290);
			img.setAlignment(Element.ALIGN_CENTER);
			doc.add(img);
		} catch (Exception e) {
			logger.warn("Failed to embed chart image; continuing without it", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<BaseRecord> sortedSections(Object sectionsObj) {
		List<BaseRecord> sections = new ArrayList<>();
		if (sectionsObj instanceof List) {
			sections.addAll((List<BaseRecord>) sectionsObj);
		}
		sections.sort(Comparator.comparingInt(s -> {
			Object o = s.get("sectionOrder");
			return o instanceof Number ? ((Number) o).intValue() : 0;
		}));
		return sections;
	}

	private static String headingLabel(String sectionType) {
		if (sectionType == null) {
			return "Section";
		}
		switch (sectionType) {
			case ReportTemplates.EXECUTIVE_SUMMARY:
				return "Executive Summary";
			case ReportTemplates.METHODOLOGY:
				return "Methodology";
			case ReportTemplates.RESULTS:
				return "Results by Module";
			case ReportTemplates.MITIGATION:
				return "Mitigation Actions";
			default:
				return sectionType;
		}
	}

	private static String hashHex(BaseRecord report) {
		try {
			Object h = report.get("hash");
			if (h instanceof byte[]) {
				byte[] b = (byte[]) h;
				StringBuilder sb = new StringBuilder(b.length * 2);
				for (byte x : b) {
					sb.append(String.format("%02x", x));
				}
				return sb.length() == 0 ? "(not set)" : sb.toString();
			}
		} catch (Exception e) {
			/* fall through */
		}
		return "(not set)";
	}

	private static String joinList(Object listObj) {
		if (listObj instanceof List) {
			List<?> list = (List<?>) listObj;
			List<String> parts = new ArrayList<>();
			for (Object o : list) {
				parts.add(String.valueOf(o));
			}
			return parts.isEmpty() ? "(none)" : String.join(", ", parts);
		}
		return "(none)";
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	private static long lng(BaseRecord r, String field) {
		try {
			Object v = r.get(field);
			if (v instanceof Number) {
				return ((Number) v).longValue();
			}
		} catch (Exception e) {
			/* default */
		}
		return 0L;
	}
}
