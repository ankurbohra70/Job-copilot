package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import static com.jobcopilot.resume.CandidateProfileData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DeterministicProfileParserV5Test {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);
    private final DeterministicProfileParser parser = new DeterministicProfileParser();
    private static PartialDate year(int year) { return new PartialDate(year, null, DatePrecision.YEAR); }
    private static String fixture(String name) throws Exception {
        try (var input = DeterministicProfileParserV5Test.class.getResourceAsStream("/resume/deterministic/" + name + ".txt")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ankur_Bohra_Amazon", "Ankur_Bohra_Backend_GenAI", "Ankur_Bohra_SDE", "AnkurBohra_AI", "AnkurBohra_SDeee"})
    void fiveResumeMatrix(String name) throws Exception {
        String text = fixture(name);
        var extracted = new DeterministicCandidateProfileExtractor(parser).extract(text, DAY);
        var data = extracted.profile();
        assertEquals("rules-v5", extracted.extractorVersion());
        assertEquals("v1", extracted.vocabularyVersion());
        assertEquals(data, parser.parse(text, DAY));
        assertEquals(data, new CandidateProfileValidator().canonicalize(data));
        assertEquals(2, data.projects().size());
        if (name.startsWith("Ankur_Bohra_")) {
            boolean sde = name.endsWith("SDE");
            boolean amazon = name.endsWith("Amazon");
            assertEquals(sde ? 1 : 2, data.workExperience().size());
            assertEquals(2, data.education().size());
            assertEquals(Assessment.KNOWN, data.experienceAssessment());
            assertEquals(sde ? 15 : 13, data.totalExperienceMonths());
            assertEquals(sde ? List.of(4) : amazon ? List.of(5, 4) : List.of(6, 2),
                    data.workExperience().stream().map(w -> w.bullets().size()).toList());
            assertEquals(sde ? List.of(3, 3) : amazon ? List.of(3, 2) : List.of(4, 2),
                    data.projects().stream().map(p -> p.bullets().size()).toList());
        } else {
            assertEquals(List.of(), data.workExperience());
            assertEquals(Assessment.UNKNOWN, data.experienceAssessment());
            assertNull(data.totalExperienceMonths());
            assertNull(data.observedExperienceMonths());
            assertEquals(List.of(), data.roleCategories());
            assertEquals(name.endsWith("AI") ? List.of("c++", "python") : List.of("c++", "git", "javascript", "python"), data.skills());
            assertNewEducation(data);
            assertNewProjects(name, data);
        }
        for (Project project : data.projects()) {
            assertTrue(text.replace("\r", "").contains(project.sourceText()));
            assertFalse(project.sourceText().contains("Positions of Responsibility"));
            assertFalse(project.sourceText().contains("Vice President"));
            assertFalse(project.sourceText().contains("Competitive Programmers Lead"));
            assertFalse(project.sourceText().contains("Treasurer"));
            assertTrue(project.bullets().stream().noneMatch(b -> b.contains("\n")));
        }
    }

    private static void assertNewEducation(CandidateProfileData data) {
        assertEquals(List.of(
                new Education("National Institute of Technology, Tiruchirappalli", "Master of Computer Applications (MCA)", year(2021), year(2024),
                        "National Institute of Technology, Tiruchirappalli 2021 – 2024\nMaster of Computer Applications (MCA) - CGPA - 7.26 Trichy, Tamil Nadu"),
                new Education("Deen Dayal Upadhyaya College, University of Delhi", "B.Sc Honours Computer Science", year(2018), year(2021),
                        "Deen Dayal Upadhyaya College, University of Delhi 2018 – 2021\nB.Sc Honours Computer Science - CGPA - 7.47 New Delhi"),
                new Education(null, "Higher Secondary XII (CBSE)", year(2016), year(2018), "Higher Secondary XII (CBSE) - 89.75% 2016 – 2018"),
                new Education(null, "Secondary X (ICSE)", null, year(2016), "Secondary X (ICSE), - 92.40% 2016")), data.education());
    }

    private static void assertNewProjects(String name, CandidateProfileData data) {
        List<String> titles;
        List<List<String>> bullets;
        if (name.endsWith("AI")) {
            titles = List.of("DNA Sequencing Monitoring Diseases", "Customer Behavior Analysis in Supermarkets");
            bullets = List.of(List.of(
                    "• Developed an LSTM-ARIMA hybrid model to predict disease progression based on genomic time-series data.",
                    "• Integrated clinical and genomic datasets using bioinformatics pipelines for precision medicine applications.",
                    "• Visualized large-scale data insights using Tableau and Power BI dashboards."), List.of(
                    "• Applied Apriori and FP-Growth for association rule mining to uncover frequent itemsets and purchase sequences.",
                    "• Clustered customers using k-means and classified behaviors with decision trees for personalized marketing.",
                    "• Deployed Spark-based pipeline for efficient transaction processing and visualization with Power BI."));
        } else {
            titles = List.of("ReClothenation", "Technofania Website");
            bullets = List.of(List.of(
                    "• Eco-friendly clothing donation platform promoting reuse and recycling of used clothes by facilitating contributions to NGOs and Recycling companies.",
                    "• Implemented features such as Search and filtering, recycling and reuse options, sustainability tips, educational resources",
                    "• Revolutionizing sustainable fashion and reducing waste."), List.of(
                    "• Developed website for departmental club, Open Source Open Community, technical event (Hosted on Digital Ocean)",
                    "• Participants could register at the site and participate in the Technical and Non-Technical event"));
        }
        assertEquals(titles, data.projects().stream().map(Project::title).toList());
        assertEquals(bullets, data.projects().stream().map(Project::bullets).toList());
        List<String> sources = name.endsWith("AI") ? List.of(
                "DNA Sequencing Monitoring Diseases | Python, Deep learning, Time Series Analysis Project Link\n" + String.join("\n", bullets.get(0)),
                "Customer Behavior Analysis in Supermarkets | Python, ML, Hadoop, Apache Spark, Power BI Project Link\n" + String.join("\n", bullets.get(1))) : List.of(
                "ReClothenation | HTML, CSS, JS, Bootstrap, NodeJS, MongoDB, EJS Project Link\n"
                        + "• Eco-friendly clothing donation platform promoting reuse and recycling of used clothes by facilitating contributions to\nNGOs and Recycling companies.\n"
                        + bullets.get(0).get(1) + "\n" + bullets.get(0).get(2),
                "Technofania Website | HTML, CSS, Javascript, NodeJS, ExpressJs, ReactJS, MongoDB Project Link\n" + String.join("\n", bullets.get(1)));
        assertEquals(sources, data.projects().stream().map(Project::sourceText).toList());
        for (int i = 0; i < 2; i++) {
            Project p = data.projects().get(i);
            assertFalse(p.sourceText().contains(titles.get(1 - i)));
            assertEquals(name.endsWith("AI") ? List.of("python") : List.of("javascript"), p.technologies());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"AnkurBohra_AI", "AnkurBohra_SDeee"})
    void originalLocalPdfsMatchFaithfulSnapshots(String name) throws Exception {
        String directory = System.getProperty("rulesv5.localPdfDirectory");
        assumeTrue(directory != null, "Local personal PDF verification requires -Drulesv5.localPdfDirectory");
        var extraction = new PdfBoxResumeTextExtractor(new ResumeProcessingProperties(5_242_880, 25, 200_000, 50))
                .extract(Files.readAllBytes(Path.of(directory, name + ".pdf")));
        assertEquals(fixture(name).replace("\r", ""), extraction.text().replace("\r", ""));
        assertEquals(parser.parse(fixture(name), DAY), parser.parse(extraction.text(), DAY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Secondary X (ICSE), - 92.40% 2016", "Secondary X (ICSE), - 92.40%\n2016", "Secondary X (ICSE)\n\n2016"})
    void singleCompletionYearHasNoInventedStartOrInstitution(String text) {
        var data = parser.parse("Education\n" + text, DAY);
        assertEquals(List.of(new Education(null, "Secondary X (ICSE)", null, year(2016), text)), data.education());
    }

    @Test
    void namedSchoolAndAcademicCompletionWithGrades() {
        var data = parser.parse("Education\nExample School - Secondary X (ICSE) - 92.40% 2016\n"
                + "Example University - Master of Science - CGPA 8.2\n2024", DAY);
        assertEquals(2, data.education().size());
        assertEquals("Example School", data.education().getFirst().institution());
        assertEquals("Secondary X (ICSE)", data.education().getFirst().qualification());
        assertEquals("Example University", data.education().get(1).institution());
        assertEquals("Master of Science", data.education().get(1).qualification());
        assertNull(data.education().get(1).start());
        assertEquals(year(2024), data.education().get(1).end());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Won an award in 2017", "University scholarship awarded in 2017", "University Scholarship Award 2017", "Example School ID 2017", "Example University Grade 2017", "Student ID 2017", "CGPA 2017", "Grade 2017%", "ID-X2017", "Secondary X (ICSE) 1899", "Secondary X (ICSE) 2201", "• Secondary X (ICSE) 2017"})
    void incidentalYearsDoNotSplitEducation(String incidental) {
        String first = "Higher Secondary XII (CBSE) - 89.75% 2016 - 2018\n" + incidental;
        var data = parser.parse("Education\n" + first + "\nSecondary X (ICSE), - 92.40% 2016", DAY);
        assertEquals(2, data.education().size());
        assertEquals(first, data.education().getFirst().sourceText());
        assertEquals("Higher Secondary XII (CBSE)", data.education().getFirst().qualification());
        assertEquals(year(2016), data.education().get(1).end());
    }

    @ParameterizedTest
    @ValueSource(strings = {"in", "a", "an", "and", "of", "for", "the", "with", "to"})
    void projectConnectorsStillRequirePositiveContext(String connector) {
        String title = String.join(" ", "Analysis", connector, "Systems");
        var data = parser.parse("Projects\nAlpha | GitHub\n- Built services\n"
                + title + " | Python Project Link\n- Shipped analysis", DAY);
        assertEquals(List.of("Alpha", title), data.projects().stream().map(Project::title).toList());
        assertEquals(List.of("- Built services"), data.projects().getFirst().bullets());
    }

    @Test
    void proseAndTitlesWithoutContextRemainContinuations() {
        var data = parser.parse("Projects\nAlpha | GitHub\n- Built services\n"
                + "worked in supermarkets\nAnalysis in Systems\n"
                + "Worked in supermarkets | Python Project Link\nread more in this link\n- Shipped changes", DAY);
        assertEquals(1, data.projects().size());
        assertEquals(List.of("- Built services worked in supermarkets Analysis in Systems Worked in supermarkets | Python Project Link read more in this link", "- Shipped changes"), data.projects().getFirst().bullets());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Positions of Responsibility", "Position of Responsibility", "Leadership", "Leadership Experience"})
    void exactOtherHeadingsBoundRepeatedProjectSections(String heading) {
        String first = "Alpha | GitHub\n- Built a tool";
        String second = "Beta | GitHub\n- Built another tool";
        var data = parser.parse("Projects\n" + first + "\n  " + heading.toUpperCase() + " :  \nVice President\n"
                + "Jan 2020 - Dec 2020\n- Led students\nProjects\n" + second + "\n" + heading.toLowerCase()
                + "\nTreasurer\n- Managed funds", DAY);
        assertEquals(List.of(first, second), data.projects().stream().map(Project::sourceText).toList());
        assertEquals(List.of(List.of("- Built a tool"), List.of("- Built another tool")), data.projects().stream().map(Project::bullets).toList());
        assertEquals(List.of(), data.workExperience());
        assertEquals(Assessment.UNKNOWN, data.experienceAssessment());
    }

    @Test
    void headingSubstringsAreNotSectionBoundaries() {
        var data = parser.parse("Projects\nAlpha | GitHub\n- Built a tool\n"
                + "Discussed Positions of Responsibility in the design\n- Shipped changes", DAY);
        assertEquals(List.of("- Built a tool Discussed Positions of Responsibility in the design", "- Shipped changes"), data.projects().getFirst().bullets());
    }
}
