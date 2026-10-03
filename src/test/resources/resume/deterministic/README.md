# Deterministic parser fixtures

New layout-v2/facts-v1 committed acceptance fixtures live in `../facts/` and are synthetic. Do not add private
master-resume content to these fixtures. `master-shape.txt` retains only the acceptance structure: contact
header, professional profiles, two work records, two education records, two projects, skills, and achievements.
Layout parity tests compare the exact legacy PDFBox recipe with the observer on the same platform and compare
the entire rules-v5 profile at a fixed date; they do not relax Unicode or whitespace equality.

Rules-v5 adds `AnkurBohra_AI.txt` and `AnkurBohra_SDeee.txt`, extracted unchanged from the original local PDFs in Downloads with PDFBox 3.0.8, positional sorting, and NUL removal. The two personal PDFs are not copied into the repository. Their extracted text retains physical wraps and font-icon extraction artifacts. Optional end-to-end verification uses `-Drulesv5.localPdfDirectory=C:/Users/mitta/Downloads`; without that property, the two local-PDF parity tests are skipped while all text-fixture regression tests still run.

Reviewed v5 expectations for both new layouts: no professional work, UNKNOWN experience, four education records, and two projects. AI project bullet counts are 3 / 3; SDeee counts are 3 / 2. School records retain grades only in sourceText, use null institutions, and store Secondary X's single year as an end date with YEAR precision. Positions of Responsibility is an unparsed section boundary. Skills and vocabulary v1 are unchanged. Fresh extraction uses rules-v5; persisted rules-v1 through rules-v4 profiles are not re-extracted.

The three `Ankur_Bohra_*.pdf` files are the original manual-QA resumes provided locally by the user. Their matching UTF-8 `.txt` files were extracted with PDFBox 3.0.8, `PDFTextStripper.setSortByPosition(true)`, and the same NUL removal as `PdfBoxResumeTextExtractor`. Text is not manually repaired or bullet-normalized. Tests compare production PDF extraction with these snapshots, ignoring only platform carriage returns.

At assessment date 2026-10-03, reviewed project expectations are:

| Resume | Project | Logical bullets |
| --- | --- | --- |
| Amazon | Job Copilot - AI Job Intelligence Platform | 3 |
| Amazon | Raahi - Full-Stack Ride Booking Platform | 2 |
| Backend/GenAI | Job Copilot - AI Job Discovery, Intelligence & Matching Platform | 4 |
| Backend/GenAI | Raahi - Ride Booking Backend | 2 |
| SDE | Raahi App | 3 |
| SDE | Personalized Travel Recommender | 3 |

Amazon uses U+2022 bullets, Backend/GenAI uses hyphen bullets, and SDE uses U+2013 en-dash bullets. Physical line wraps are intentional. The SDE extraction also retains PDF line-end hyphenation; logical bullet folding joins whitespace without attempting lexical repair.

The existing `modern-inline.txt`, `modern-multiline.txt`, and `legacy-title-company.txt` files remain simplified structural regression fixtures.

Rules-v4 adds complete logical work-bullet assembly using the same whitespace joining as projects. Reviewed work counts are Amazon 5 / 4, Backend/GenAI 6 / 2, and SDE 4. Visible line-end hyphens remain visible (`re-` followed by `duction` becomes `re- duction` in structured bullets). Original PDF and text snapshots remain unchanged. Historical parser-version profiles are never automatically re-extracted.
