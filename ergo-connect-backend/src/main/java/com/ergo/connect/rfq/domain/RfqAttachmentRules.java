package com.ergo.connect.rfq.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import com.ergo.connect.rfq.domain.RfqDtos.AttachmentRule;

/**
 * Which file types each part slot accepts (client RFQ format) and the size limit. Configured in the backend
 * ({@code ergo.rfq.attachments.*} in application.yml; the defaults are the client's lists). The global document rules
 * (type allowlist, malware scan, size limit) still apply on top; this only narrows a slot.
 */
@Component
class RfqAttachmentRules {

	private final Map<FileKind, List<String>> extensions = new EnumMap<>(FileKind.class);

	private final long maxBytes;

	RfqAttachmentRules(@Value("${ergo.rfq.attachments.drawing-2d:pdf,igs,iges,doc,docx}") List<String> drawing2d,
			@Value("${ergo.rfq.attachments.model-3d:step,stp,igs,iges,zip}") List<String> model3d,
			@Value("${ergo.rfq.attachments.specification:pdf,doc,docx,igs,iges}") List<String> specification,
			@Value("${ergo.rfq.attachments.additional:pdf,jpg,jpeg,png,doc,docx,xls,xlsx,zip}") List<String> additional,
			@Value("${spring.servlet.multipart.max-file-size:50MB}") DataSize maxFileSize) {
		extensions.put(FileKind.DRAWING_2D, clean(drawing2d));
		extensions.put(FileKind.MODEL_3D, clean(model3d));
		extensions.put(FileKind.SPECIFICATION, clean(specification));
		extensions.put(FileKind.ADDITIONAL, clean(additional));
		this.maxBytes = maxFileSize.toBytes();
	}

	private static List<String> clean(List<String> list) {
		return list.stream().map(e -> e.strip().toLowerCase(Locale.ROOT)).filter(e -> !e.isEmpty()).toList();
	}

	List<AttachmentRule> all() {
		return java.util.Arrays.stream(FileKind.values()).map(k -> new AttachmentRule(k, extensions.get(k), maxBytes)).toList();
	}

	/** Whether this file name ends in an extension the slot accepts. */
	boolean accepts(FileKind kind, String fileName) {
		if (fileName == null) {
			return false;
		}
		int dot = fileName.lastIndexOf('.');
		return dot >= 0 && extensions.get(kind).contains(fileName.substring(dot + 1).strip().toLowerCase(Locale.ROOT));
	}

	List<String> extensions(FileKind kind) {
		return extensions.get(kind);
	}

}
