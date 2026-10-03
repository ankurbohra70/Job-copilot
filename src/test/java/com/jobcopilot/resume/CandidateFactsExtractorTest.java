package com.jobcopilot.resume;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jobcopilot.resume.ResumeDocument.*;

class CandidateFactsExtractorTest {
    static final LocalDate DAY = LocalDate.of(2026, 10, 3);
    static byte[] masterPdf() throws IOException {
        try (var stream = CandidateFactsExtractorTest.class.getResourceAsStream("/resume/facts/master-shape.txt")) {
            return pdf(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    static byte[] pdf(String text) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                int index = 0;
                for (String line : text.split("\n")) LayoutExtractionTest.text(content, line, 40, 750 - 18 * index, index++ == 0 ? 18 : 10);
            }
            document.save(out); return out.toByteArray();
        }
    }
    private CandidateFactExtraction extract(String text) throws IOException { return extract(pdf(text)); }
    private CandidateFactExtraction extract(byte[] pdf) {
        var extraction = new PdfBoxResumeTextExtractor(new ResumeProcessingProperties(5242880,25,200000,1)).extract(pdf);
        var profile = new CandidateProfileValidator().canonicalize(new DeterministicProfileParser().parse(extraction.text(), DAY));
        return new DeterministicCandidateFactsExtractor().extract(extraction.document(), profile, DAY);
    }
    @Test void sanitizedMasterShapeContactFactsLinksAndOngoingTitle() throws Exception {
        var extraction = extract(masterPdf()); var facts = extraction.facts();
        assertEquals("Ada Lovelace", facts.fullName()); assertEquals("Ada@example.com", facts.email());
        assertEquals("+442079460018", facts.phone()); assertEquals("London, United Kingdom", facts.location());
        assertEquals("Backend Engineer", facts.currentTitle());
        assertEquals(List.of(ProfessionalLink.Type.LINKEDIN, ProfessionalLink.Type.GITHUB, ProfessionalLink.Type.LEETCODE),
                facts.professionalLinks().stream().map(ProfessionalLink::type).toList());
        assertEquals(CandidateFactState.UNKNOWN, facts.workAuthorization());
        assertEquals(CandidateFactState.UNKNOWN, facts.sponsorshipRequired()); assertNull(facts.noticePeriodDays());
        assertNull(facts.totalRelevantExperienceMonths()); assertEquals("facts-v1", extraction.factExtractorVersion());
        assertEquals(8, extraction.metadata().evidence().size());
        assertEquals(extraction, extract(masterPdf()));
        var phoneEvidence = extraction.metadata().evidence().stream().filter(e -> e.field().equals("phone")).findFirst().orElseThrow();
        assertEquals("+44 20 7946 0018", phoneEvidence.rawValue());
    }
    @Test void missingHeaderAndBodyContactLocationAndLinksAreNotPromoted() throws Exception {
        var facts = extract("Experience\nEngineer at Acme\nJan 2024 - Dec 2024\nLondon, United Kingdom\nbody@example.com\nEducation\nUniversity in Paris\nProjects\nAlpha | GitHub\nhttps://github.com/ada/project\nSkills\nJava").facts();
        assertNull(facts.fullName()); assertNull(facts.email()); assertNull(facts.phone()); assertNull(facts.location());
        assertNull(facts.currentTitle()); assertTrue(facts.professionalLinks().isEmpty());
    }
    @Test void competingContactsAndNamesStayNull() throws Exception {
        var result = extract("Name: Ada Lovelace\nName: Grace Hopper\nada@example.com | grace@example.com\nPhone: +44 20 7946 0018 | Phone: +1 650 253 0000\nLocation: London\nLocation: Paris\nExperience");
        assertNull(result.facts().fullName()); assertNull(result.facts().email()); assertNull(result.facts().phone()); assertNull(result.facts().location());
        assertTrue(result.metadata().diagnostics().containsAll(List.of("AMBIGUOUS_FULLNAME", "AMBIGUOUS_EMAIL", "AMBIGUOUS_PHONE", "AMBIGUOUS_LOCATION")));
    }
    @ParameterizedTest @ValueSource(strings = {"Example Technologies", "Backend Engineer", "Skills", "A random introductory sentence", "Ada Lovelace"})
    void firstLineWithoutContactContextIsNotAName(String line) throws Exception {
        assertNull(extract(line + "\nEducation\nExample University").facts().fullName());
    }
    @ParameterizedTest @ValueSource(strings = {"Phone: 2020 - 2025", "Phone: +999 1234567890", "Phone: 0000000000", "CGPA: 7.26", "ID: 9876543210"})
    void malformedNumbersDatesAndIdentifiersAreNotPhones(String line) throws Exception {
        assertNull(extract("Ada Lovelace\nada@example.com\n" + line).facts().phone());
    }
    @ParameterizedTest @ValueSource(strings = {"ada@@example.com", "ada@invalid", "ada..test@example.com", "ada@-example.com"})
    void malformedEmailDoesNotPrefill(String value) throws Exception {
        assertNull(extract("Ada Lovelace\nEmail: " + value).facts().email());
    }
    @Test void countrylessLabelledNumberPreservesRawWithoutCountryGuess() throws Exception {
        var result = extract("Ada Lovelace\nada@example.com\nPhone: 98765 43210\nExperience\nEngineer at Acme in India");
        assertEquals("98765 43210", result.facts().phone());
    }
    @Test void standaloneCountrylessContactNumberPreservesRaw() throws Exception {
        assertEquals("9876543210",extract("Ada Lovelace\nada@example.com\n9876543210\nEducation").facts().phone());
    }
    @Test void contactRowLocationIsBoundedAndBodyLocationsRemainExcluded() throws Exception {
        var facts = extract("Ada Lovelace\nada@example.com | London, United Kingdom\nExperience\nEngineer at Acme\nJan 2024 - Dec 2024\nParis, France\nEducation\nBerlin, Germany").facts();
        assertEquals("London, United Kingdom",facts.location());
    }
    @Test void unrelatedDefectIsNotSwallowed() {
        assertThrows(NullPointerException.class,()->new DeterministicCandidateFactsExtractor().extract(null,profile(List.of()),DAY));
    }
    @Test void uriAnnotationsRequireMatchingVisibleContactLabel() throws Exception {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                LayoutExtractionTest.text(content, "Ada Lovelace", 40, 750, 18);
                LayoutExtractionTest.text(content, "ada@example.com", 40, 730, 10);
                LayoutExtractionTest.text(content, "GitHub", 40, 710, 10);
                LayoutExtractionTest.text(content, "Engineer", 40, 690, 10);
                LayoutExtractionTest.text(content, "Projects", 40, 670, 10);
                LayoutExtractionTest.text(content, "GitHub", 40, 650, 10);
            }
            for (int y : new int[] {706, 686, 646, 600}) {
                var link = new PDAnnotationLink(); var action = new PDActionURI(); action.setURI("https://github.com/ada");
                link.setAction(action); link.setRectangle(new PDRectangle(40,y,40,14)); page.getAnnotations().add(link);
            }
            document.save(out); var result = extract(out.toByteArray());
            assertEquals(List.of(new ProfessionalLink(ProfessionalLink.Type.GITHUB,"https://github.com/ada")), result.facts().professionalLinks());
            assertEquals(CandidateFactExtractionData.Derivation.PDF_URI_ANNOTATION,
                    result.metadata().evidence().stream().filter(e -> e.field().equals("professionalLinks")).findFirst().orElseThrow().derivation());
        }
    }
    @Test void repositoryAndLookalikeHostsDoNotBecomeGithubProfiles() throws Exception {
        var links = extract("Ada Lovelace\nada@example.com\nhttps://github.com/ada/repo\nhttps://github.com.evil.example.com/ada\nhttps://leetcode.com/problems/two-sum\nhttps://linkedin.com/company/example").facts().professionalLinks();
        assertTrue(links.stream().noneMatch(l -> l.type() == ProfessionalLink.Type.GITHUB || l.type() == ProfessionalLink.Type.LINKEDIN || l.type() == ProfessionalLink.Type.LEETCODE));
    }
    private static CandidateProfileData profile(List<CandidateProfileData.WorkExperience> work) {
        return new CandidateProfileData(List.of(),null,null,CandidateProfileData.Assessment.UNKNOWN,work,List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
    private static CandidateProfileData.WorkExperience work(boolean ongoing, int year, Integer month) {
        return new CandidateProfileData.WorkExperience("Engineer","Acme",new CandidateProfileData.PartialDate(year,month,
                month == null ? CandidateProfileData.DatePrecision.YEAR : CandidateProfileData.DatePrecision.MONTH),null,ongoing,List.of(),"Engineer at Acme");
    }
    @Test void currentTitleOnlyFromOneNonFutureOngoingEntry() {
        var extractor = new DeterministicCandidateFactsExtractor(); var document = ResumeDocument.unavailable("", "CONTACT_REGION_UNAVAILABLE");
        assertEquals("Engineer", extractor.extract(document, profile(List.of(work(true,2025,1))), DAY).facts().currentTitle());
        for (var entries : List.of(List.<CandidateProfileData.WorkExperience>of(), List.of(work(false,2025,1)),
                List.of(work(true,2027,null)), List.of(work(true,2026,11)), List.of(work(true,2025,1),work(true,2024,1))))
            assertNull(extractor.extract(document,profile(entries),DAY).facts().currentTitle());
    }
    @Test void unicodeNamesSupportedWithExplicitLabelAndContactContext() {
        var b = new Bounds(40,50,150,12);
        var lines = List.of(new ResumeLine("p1-l1","Name: \u00c9lodie O'Connor-Smith",0,b,List.of(new ResumeTextSpan("Name: \u00c9lodie O'Connor-Smith",b,12,"Test",0))),
                new ResumeLine("p1-l2","elodie@example.com",1,new Bounds(40,70,150,12),List.of(new ResumeTextSpan("elodie@example.com",new Bounds(40,70,150,12),12,"Test",0))));
        var document = new ResumeDocument("",List.of(new ResumePage(1,600,800,0,0,0,lines,List.of())),true,List.of());
        assertEquals("\u00c9lodie O'Connor-Smith",new DeterministicCandidateFactsExtractor().extract(document,profile(List.of()),DAY).facts().fullName());
    }
    @Test void noRawTextFallbackWhenLayoutUnavailable() {
        var result = new DeterministicCandidateFactsExtractor().extract(ResumeDocument.unavailable("Ada Lovelace\nada@example.com", "LAYOUT_POSITION_LIMIT"),profile(List.of()),DAY);
        assertNull(result.facts().fullName()); assertNull(result.facts().email()); assertTrue(result.metadata().diagnostics().contains("LAYOUT_POSITION_LIMIT"));
    }
}
