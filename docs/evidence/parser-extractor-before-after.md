# Parser / Extractor Before-After Evidence

Updated: 2026-05-17

이 문서는 `evaluation/parser-extractor`의 compact fixture를 최종 보고서에 옮길 수 있는 before/after evidence로 정리합니다.
원본 runtime dump는 Git에 넣지 않고, 작은 JSONL fixture와 재현 명령만 보존합니다.

## Reproduction

```bash
python3 evaluation/parser-extractor/scripts/compare_snapshots.py \
  evaluation/parser-extractor/fixtures/youtube_search_raw.jsonl \
  evaluation/parser-extractor/fixtures/youtube_search_cleaned.jsonl

python3 evaluation/parser-extractor/scripts/compare_snapshots.py \
  evaluation/parser-extractor/fixtures/chrome_search_raw.jsonl \
  evaluation/parser-extractor/fixtures/chrome_search_cleaned.jsonl
```

## YouTube Search Snapshot

| State | Text | Reason / Source | Bounds |
| --- | --- | --- | --- |
| Raw | tlqkf | youtube_search_input | 92,42,430,100 |
| Raw | All | filter_chip | 20,126,82,178 |
| Raw | Shorts | filter_chip | 98,126,202,178 |
| Raw | Tlqkf 또 다시 보여줘야돼!!! | thumbnail_visual_or_title | 32,150,384,194 |
| Raw | "Tlqkf 또 보여줘야 돼!" : 식케이 (Sik-K), Lil Moshpit - LOV3 | video_title | 100,570,610,650 |
| Raw | 세모플 semo playlist · 917K views · 7 months ago | metadata | 100,650,610,690 |
| Raw | 11 chapters | chapter_ui | 20,710,150,776 |
| Raw | sampleuser | comment_author | 120,820,420,860 |
| Raw | 7 months ago | metadata | 430,820,620,860 |
| Raw | tlqkf 뭐냐 진짜 | comment_body | 120,880,720,940 |
| Kept | tlqkf | user_input | 92,42,430,100 |
| Kept | "Tlqkf 또 보여줘야 돼!" : 식케이 (Sik-K), Lil Moshpit - LOV3 | accessibility_title | 100,570,610,650 |
| Kept | tlqkf 뭐냐 진짜 | accessibility_comment | 120,880,720,940 |
| Removed | All | filter_chip |  |
| Removed | Shorts | filter_chip |  |
| Removed | 세모플 semo playlist · 917K views · 7 months ago | metadata |  |
| Removed | 11 chapters | chapter_ui |  |
| Removed | 7 months ago | metadata |  |

## Chrome Search Snapshot

| State | Text | Reason / Source | Bounds |
| --- | --- | --- | --- |
| Raw | google.com/search?q=tlqkf | browser_address_bar | 80,36,720,92 |
| Raw | All | search_tab | 20,116,88,166 |
| Raw | What is 'Tlqkf'?_Contemporary Korean Slang | search_result_title | 42,356,702,430 |
| Raw | Contemporary Korean Slang · 40K views · 5 years ago | metadata | 42,430,690,480 |
| Raw | 'Tlqkf' is a romanized Korean slang spelling sometime... | search_result_snippet | 42,488,700,560 |
| Kept | What is 'Tlqkf'?_Contemporary Korean Slang | browser_accessibility_title | 42,356,702,430 |
| Kept | 'Tlqkf' is a romanized Korean slang spelling sometime... | browser_accessibility_snippet | 42,488,700,560 |
| Removed | google.com/search?q=tlqkf | browser_address_bar |  |
| Removed | All | search_tab |  |
| Removed | Contemporary Korean Slang · 40K views · 5 years ago | metadata |  |

## Cleaning Rules Demonstrated

| Rule | Evidence in fixtures | Why it matters |
| --- | --- | --- |
| Filter/navigation chip removal | `All`, `Shorts` removed | UI controls should not be sent to the classifier |
| Metadata removal | view count, age, chapter, channel metadata removed | Prevents non-user-content from becoming model input |
| Browser chrome removal | address bar and search tab removed | Chrome/Google UI should not be interpreted as comments |
| Candidate preservation | title/snippet/comment/search input kept with source and bounds | Keeps text that may need model analysis or masking |
| Android bounds preservation | kept rows retain `boundsInScreen` | Overlay masking needs screen coordinates, not just text |

## Cleaned JSONL To Model Input

The cleaned fixture keeps only model-relevant candidates plus screen geometry.

```json
{
  "timestamp": 1778339680523,
  "platform": "youtube",
  "screen": "search_results",
  "comments": [
    {
      "author_id": "android-accessibility-comment:youtube",
      "commentText": "tlqkf 뭐냐 진짜",
      "boundsInScreen": { "left": 120, "top": 880, "right": 720, "bottom": 940 },
      "source": "accessibility_comment"
    }
  ]
}
```

This can be uploaded to `/analyze_android` as candidate text plus geometry.
The backend returns classifier scores and `evidence_spans`; Android can then project the span onto the candidate `boundsInScreen` or fall back to hiding the candidate region when span projection is not stable.

## Remaining Gaps

| Gap | Needed evidence |
| --- | --- |
| Instagram and TikTok are not represented in compact fixtures | Add one raw/cleaned pair per platform |
| Duplicate removal is documented but not shown here | Add a fixture with repeated scroll/accessibility rows |
| No-comment/non-target screen detection is not shown here | Add one negative fixture for home/settings/non-comment screens |
| Final mask result is not included | Pair cleaned JSONL with backend response and overlay screenshot |

