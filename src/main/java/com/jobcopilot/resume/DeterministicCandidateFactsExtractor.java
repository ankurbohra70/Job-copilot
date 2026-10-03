package com.jobcopilot.resume;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import static com.jobcopilot.resume.ResumeDocument.*;
import static com.jobcopilot.resume.CandidateFactExtractionData.*;

@Component
public final class DeterministicCandidateFactsExtractor implements CandidateFactsExtractor {
    public static final String VERSION = "facts-v1";
    private static final Pattern EMAIL = Pattern.compile("(?<![\\p{L}\\p{N}_.%+@-])([A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+)@([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+)(?![\\p{L}\\p{N}_@-])");
    private static final Pattern PHONE = Pattern.compile("(?<![\\p{L}\\p{N}])(?:\\+\\s*)?\\d[\\d () .-]{5,30}\\d(?![\\p{L}\\p{N}])");
    private static final Pattern PHONE_LABEL = Pattern.compile("(?i)\\b(?:phone|mobile|tel(?:ephone)?|contact number)\\s*[:：]?");
    private static final Pattern URL = Pattern.compile("(?i)(?:https?://[^\\s<>|]+|(?<![@\\p{L}\\p{N}])(?:www\\.)?[a-z0-9][a-z0-9.-]*\\.(?:com|org|net|io|dev|me|in|co)(?:/[^\\s<>|]*)?)");
    private static final Pattern SECTION = Pattern.compile("(?i)^(?:experience|work experience|professional experience|employment(?: history)?|education|academic background|projects|personal projects|academic projects|skills|technical skills|summary|professional summary|certifications|achievements|awards(?: and achievements| & achievements)?|positions? of responsibility|leadership(?: experience| and achievements| & achievements)?)(?:\\s*[:：].*)?$");
    private static final Pattern NOT_NAME = Pattern.compile("(?i)\\b(?:resume|curriculum|vitae|engineer|developer|intern|architect|consultant|analyst|scientist|manager|lead|university|college|institute|school|company|corporation|inc|ltd|limited|technologies|solutions|systems|group|software|services|education|experience|projects|skills|contact|profile|portfolio|website|github|linkedin|leetcode)\\b");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{M}]+(?:[’'\\-][\\p{L}\\p{M}]+)*(?: +[\\p{L}\\p{M}]+(?:[’'\\-][\\p{L}\\p{M}]+)*){1,5}");
    private static final Pattern LOCATION_LABEL = Pattern.compile("(?i)^(?:location|address|based in|residence)\\s*[:：]\\s*(.+)$");
    private final PhoneNumberUtil phones = PhoneNumberUtil.getInstance();
    private record Contact(String text, ResumeLine line, Bounds bounds, float fontSize) {}
    private record Suggestion(String value, String raw, Contact contact, Derivation derivation) {}

    @Override public CandidateFactExtraction extract(ResumeDocument document, CandidateProfileData profile, LocalDate assessedOn) {
        Objects.requireNonNull(document); Objects.requireNonNull(profile); Objects.requireNonNull(assessedOn);
        var evidence = new ArrayList<FactEvidence>(); var diagnostics = new LinkedHashSet<String>();
        List<Contact> header = header(document);
        if (!document.layoutAvailable()) diagnostics.addAll(document.diagnostics());
        else if (header.isEmpty()) diagnostics.add("CONTACT_REGION_UNAVAILABLE");
        var emails = new LinkedHashMap<String, Suggestion>(); var numbers = new LinkedHashMap<String, Suggestion>();
        var locations = new LinkedHashMap<String, Suggestion>();
        for (Contact contact : header) {
            var email = EMAIL.matcher(contact.text());
            while (email.find()) {
                String local = email.group(1), domain = email.group(2).toLowerCase(Locale.ROOT);
                if (local.startsWith(".") || local.endsWith(".") || local.contains("..") || local.length() > 64 || local.length() + domain.length() + 1 > 320) continue;
                String value = local + "@" + domain;
                emails.putIfAbsent(value, new Suggestion(value, email.group(), contact, Derivation.HEADER_TEXT));
            }
            String numericText = URL.matcher(EMAIL.matcher(contact.text()).replaceAll(" ")).replaceAll(" ");
            var phone = PHONE.matcher(numericText);
            while (phone.find()) {
                String raw = phone.group().strip();
                boolean supportedRaw = PHONE_LABEL.matcher(contact.text()).find()
                        || contact.text().strip().equals(raw) && header.stream().anyMatch(c -> EMAIL.matcher(c.text()).find());
                String value = phone(raw, supportedRaw);
                if (value != null) numbers.putIfAbsent(value.replaceAll("[ () .-]", ""), new Suggestion(value, raw, contact, Derivation.HEADER_TEXT));
            }
            var labelledLocation = LOCATION_LABEL.matcher(contact.text());
            if (labelledLocation.matches()) {
                String value = labelledLocation.group(1).strip();
                if (safeLocation(value)) locations.putIfAbsent(value.toLowerCase(Locale.ROOT), new Suggestion(value, value, contact, Derivation.HEADER_TEXT));
            } else if (safeLocation(contact.text()) && contact.text().contains(",")
                    && header.stream().anyMatch(other -> other.line().reference().equals(contact.line().reference())
                        && (EMAIL.matcher(other.text()).find() || PHONE_LABEL.matcher(other.text()).find()))) {
                locations.putIfAbsent(contact.text().toLowerCase(Locale.ROOT), new Suggestion(contact.text(), contact.text(), contact, Derivation.HEADER_TEXT));
            }
        }
        String email = choose("email", emails, diagnostics, evidence);
        String phone = choose("phone", numbers, diagnostics, evidence);
        String location = choose("location", locations, diagnostics, evidence);
        var names = new LinkedHashMap<String, Suggestion>();
        float contactFont = header.stream().filter(c -> EMAIL.matcher(c.text()).find() || PHONE_LABEL.matcher(c.text()).find())
                .map(Contact::fontSize).min(Float::compare).orElse(Float.MAX_VALUE);
        boolean contactContext = !emails.isEmpty() || !numbers.isEmpty();
        for (Contact contact : header) {
            boolean labelled = contact.text().matches("(?i)^(?:full name|name)\\s*[:：].*");
            String value = labelled ? contact.text().replaceFirst("(?i)^(?:full name|name)\\s*[:：]\\s*", "") : contact.text();
            if (contactContext && NAME.matcher(value).matches() && !NOT_NAME.matcher(value).find()
                    && value.length() <= 255 && (labelled || contact.fontSize() >= contactFont * 1.15f))
                names.putIfAbsent(value.toLowerCase(Locale.ROOT), new Suggestion(value, value, contact, Derivation.HEADER_TEXT));
        }
        String name = choose("fullName", names, diagnostics, evidence);
        List<ProfessionalLink> links = links(document, header, diagnostics, evidence);
        String title = currentTitle(profile, assessedOn, diagnostics, evidence);
        var facts = new CandidateProfileFacts(name, email, phone, location, title, null,
                CandidateFactState.UNKNOWN, CandidateFactState.UNKNOWN, null, links);
        return new CandidateFactExtraction(facts, VERSION, new CandidateFactExtractionData("v1", facts, evidence, List.copyOf(diagnostics)));
    }
    private static List<Contact> header(ResumeDocument document) {
        if (!document.layoutAvailable()) return List.of();
        var page = document.pages().stream().filter(p -> p.pageNumber() == 1).findFirst();
        if (page.isEmpty() || page.get().rotation() != 0) return List.of();
        var result = new ArrayList<Contact>();
        var ordered = page.get().lines().stream().sorted(Comparator.comparingDouble(l -> l.bounds().y())).toList();
        int nonblank = 0;
        for (var line : ordered) {
            if (line.text().isBlank()) continue;
            if (line.bounds().y() > page.get().height() * .30f || ++nonblank > 20) break;
            List<Contact> segments = segments(line);
            if (segments.stream().anyMatch(c -> SECTION.matcher(c.text().strip()).matches())) break;
            if (segments.stream().anyMatch(c -> c.text().length() > 2048)) return List.of();
            result.addAll(segments);
            if (result.size() > 80) return List.of();
        }
        return List.copyOf(result);
    }
    private static List<Contact> segments(ResumeLine line) {
        var result = new ArrayList<Contact>();
        StringBuilder text = new StringBuilder(); Bounds bounds = null; float font = 0;
        for (var span : line.spans()) {
            float gap = bounds == null ? 0 : span.bounds().x() - (bounds.x() + bounds.width());
            if (bounds != null && gap > Math.max(24, font * 3)) {
                if (!text.toString().isBlank()) result.add(new Contact(text.toString().strip(), line, bounds, font));
                text.setLength(0); bounds = null; font = 0;
            }
            String[] pieces = span.text().split("[|•·]", -1);
            for (int index = 0; index < pieces.length; index++) {
                if (index > 0) {
                    if (!text.toString().isBlank()) result.add(new Contact(text.toString().strip(), line, bounds, font));
                    text.setLength(0); bounds = null; font = 0;
                }
                if (pieces[index].isBlank()) continue;
                if (!text.isEmpty()) text.append(' ');
                text.append(pieces[index]); font = Math.max(font, span.fontSize());
                // For inline separators retain the enclosing run's real bounds; do not invent character geometry.
                if (bounds == null) bounds = span.bounds();
                else bounds = new Bounds(Math.min(bounds.x(), span.bounds().x()), Math.max(bounds.y(), span.bounds().y()),
                        Math.max(bounds.x() + bounds.width(), span.bounds().x() + span.bounds().width()) - Math.min(bounds.x(), span.bounds().x()),
                        Math.max(bounds.height(), span.bounds().height()));
            }
        }
        if (!text.toString().isBlank()) result.add(new Contact(text.toString().strip(), line, bounds, font));
        return result;
    }
    private String phone(String raw, boolean labelled) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() < 7 || digits.length() > 15 || digits.chars().distinct().count() < 3
                || raw.matches(".*\\b(?:19|20)\\d{2}\\s*[-.]\\s*(?:19|20)\\d{2}.*")) return null;
        if (raw.startsWith("+")) {
            try {
                var number = phones.parse(raw, "ZZ");
                return phones.isValidNumber(number) ? phones.format(number, PhoneNumberUtil.PhoneNumberFormat.E164) : null;
            } catch (NumberParseException malformed) { return null; }
        }
        // Without an explicit country, do not use an assumed default region. Raw labelled values remain reviewable.
        return labelled && digits.length() >= 10 && raw.length() <= 64 ? raw : null;
    }
    private static boolean safeLocation(String text) {
        return text.length() <= 255 && text.matches("[\\p{L}\\p{M}][\\p{L}\\p{M} .,'’\\-]+(?: \\d{4,10})?")
                && !NOT_NAME.matcher(text).find() && !text.matches("(?i).*\\b(?:phone|email|mobile|address|location)\\b.*");
    }
    private static String choose(String field, Map<String, Suggestion> candidates, Set<String> diagnostics, List<FactEvidence> evidence) {
        if (candidates.size() != 1) {
            if (candidates.size() > 1) diagnostics.add("AMBIGUOUS_" + field.toUpperCase(Locale.ROOT));
            return null;
        }
        var suggestion = candidates.values().iterator().next();
        addEvidence(evidence, field, suggestion); return suggestion.value();
    }
    private static void addEvidence(List<FactEvidence> evidence, String field, Suggestion suggestion) {
        if (evidence.size() >= 50) return;
        var c = suggestion.contact();
        evidence.add(new FactEvidence(field, suggestion.value(), suggestion.raw(), Confidence.HIGH,
                suggestion.derivation(), snippet(c.text()), 1, c.line().reference(), c.bounds(), null));
    }
    private static String snippet(String text) { return text.length() <= 512 ? text : text.substring(0, 512); }
    private static List<ProfessionalLink> links(ResumeDocument document, List<Contact> header,
            Set<String> diagnostics, List<FactEvidence> evidence) {
        var found = new LinkedHashMap<String, Suggestion>();
        for (var contact : header) {
            var matcher = URL.matcher(contact.text());
            while (matcher.find()) {
                String raw = matcher.group().replaceAll("[.,;:)]+$", "");
                String url = safeUrl(raw);
                if (url != null && CandidateFactsValidator.classify(url) != ProfessionalLink.Type.OTHER)
                    found.putIfAbsent(url, new Suggestion(url, raw, contact, Derivation.HEADER_TEXT));
            }
        }
        if (document.layoutAvailable() && !header.isEmpty()) {
            var page = document.pages().stream().filter(p -> p.pageNumber() == 1).findFirst().orElseThrow();
            for (var link : page.hyperlinks()) {
                var contact = header.stream().filter(c -> Objects.equals(c.line().reference(), link.lineReference())
                        && c.bounds().intersects(link.bounds())).findFirst();
                if (contact.isEmpty()) continue;
                String url = safeUrl(link.uri()); if (url == null) continue;
                var type = CandidateFactsValidator.classify(url);
                if (type == ProfessionalLink.Type.OTHER) continue;
                String visible = contact.get().line().spans().stream().filter(s -> s.bounds().intersects(link.bounds()))
                        .map(ResumeTextSpan::text).reduce((a, b) -> a + " " + b).orElse("").strip();
                String lower = visible.toLowerCase(Locale.ROOT);
                var visibleUrl = URL.matcher(visible);
                if (visibleUrl.find()) {
                    String shown = safeUrl(visibleUrl.group().replaceAll("[.,;:)]+$", ""));
                    if (!Objects.equals(url, shown)) {
                        diagnostics.add("LINK_ANNOTATION_CONFLICT"); found.remove(shown); continue;
                    }
                } else if (!(type == ProfessionalLink.Type.LINKEDIN && lower.equals("linkedin")
                        || type == ProfessionalLink.Type.GITHUB && lower.equals("github")
                        || type == ProfessionalLink.Type.LEETCODE && lower.equals("leetcode")
                        || type == ProfessionalLink.Type.WEBSITE && lower.matches("(?:portfolio|website|personal website)"))) continue;
                found.putIfAbsent(url, new Suggestion(url, link.uri(), contact.get(), Derivation.PDF_URI_ANNOTATION));
            }
        }
        if (found.size() > 20) { diagnostics.add("PROFESSIONAL_LINK_LIMIT"); return List.of(); }
        var result = new ArrayList<ProfessionalLink>();
        for (var suggestion : found.values()) {
            result.add(new ProfessionalLink(CandidateFactsValidator.classify(suggestion.value()), suggestion.value()));
            addEvidence(evidence, "professionalLinks", suggestion);
        }
        return CandidateFactsValidator.canonicalLinks(result);
    }
    private static String safeUrl(String raw) {
        try { return CandidateFactsValidator.normalizeUrl(raw); }
        catch (IllegalArgumentException malformed) { return null; }
    }
    private static String currentTitle(CandidateProfileData profile, LocalDate date, Set<String> diagnostics, List<FactEvidence> evidence) {
        var ongoing = new ArrayList<Integer>();
        for (int i = 0; i < profile.workExperience().size(); i++) if (profile.workExperience().get(i).ongoing()) ongoing.add(i);
        if (ongoing.size() != 1) {
            if (ongoing.size() > 1) diagnostics.add("AMBIGUOUS_CURRENTTITLE");
            return null;
        }
        int index = ongoing.getFirst(); var work = profile.workExperience().get(index); var start = work.start();
        if (work.title() == null || work.title().isBlank()) return null;
        if (start != null && (start.year() > date.getYear() || start.year() == date.getYear()
                && start.month() != null && start.month() > date.getMonthValue())) {
            diagnostics.add("FUTURE_CURRENTTITLE"); return null;
        }
        evidence.add(new FactEvidence("currentTitle", work.title(), work.title(), Confidence.HIGH,
                Derivation.STRUCTURED_WORK, snippet(Objects.requireNonNullElse(work.sourceText(), "")),
                null, null, null, index));
        return work.title();
    }
}
