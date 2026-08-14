# F-Droid Submission Guide

This document is a reference for submitting Premium Notes to the official
F-Droid repository. It is **not** the fdroiddata metadata file — that file
lives in the [fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository
and is maintained by the F-Droid team. Copy the block below when a merge
request is opened there.

## Author

- AuthorName (display): **codeRed**
- Real name (available on request): **kusal**

## Submission checklist

1. Commit and push this repository to a public GitHub/GitLab repository.
2. Tag the release: `git tag v1.0.0 && git push origin v1.0.0`.
3. Every future release: bump `versionCode` and `versionName` in
   `app/build.gradle.kts`, then tag `vX.Y.Z`.
4. Provide screenshots (see below) and place them under
   `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
5. Request inclusion via the F-Droid submissions process or open a merge
   request against the fdroiddata repository using the metadata below.
6. Confirm to the reviewers that you are the copyright holder and that the
   project is licensed GPL-3.0-or-later.

## Required screenshots (not yet provided)

No screenshots are committed yet. For the F-Droid listing, capture and add:

- Home screen (notebook list)
- Editor with a pen stroke
- Editor with a highlighter
- Selection tool in use
- PDF export / share sheet
- Diagnostics screen

Target a phone resolution (e.g. 1080x1920). Name them descriptively and place
them in `fastlane/metadata/android/en-US/images/phoneScreenshots/`.

## Proposed fdroiddata metadata

```yaml
Categories:
  - Writing
License: GPL-3.0-or-later
AuthorName: codeRed
WebSite:
SourceCode: https://github.com/YOUR-USER/premium-notes
IssueTracker: https://github.com/YOUR-USER/premium-notes/issues

AutoName: Premium Notes
Summary: Offline handwriting notes with palm rejection
Description: |
  Premium Notes is a private, fully offline handwriting notes application.
  A built-in software palm-rejection pipeline analyzes raw touch input to
  reliably ignore palm rests so only your pen or writing finger leaves ink.

  Features: pen, highlighter, eraser and selection tools; multi-page
  notebooks; infinite canvas with pinch-to-zoom; PDF export with sharing;
  light and dark themes; input diagnostics. 100% offline with no accounts,
  no tracking, no ads, and no permissions requested.

RepoType: git
Repo: https://github.com/YOUR-USER/premium-notes

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.0.0
CurrentVersionCode: 1
```

> Replace `YOUR-USER` with the real repository owner when the repo is public.
