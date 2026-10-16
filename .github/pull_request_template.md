## What and why

<!-- One logical change per pull request. Describe the behavior before and after. -->

## How it was tested

- [ ] `./mvnw verify` (unit tests, UI tests, update site)
- [ ] `./mvnw test -f tests/unit/pom.xml -Pintegration` (real Neovim), if `core` changed
- [ ] Tried in Eclipse on: <!-- Linux / Windows / macOS -->

## Checklist

- [ ] New logic that needs no Eclipse lives in `util` or `core`, with unit tests
- [ ] No blocking Neovim calls on the SWT UI thread
- [ ] New source files carry the SPDX header
- [ ] Documentation updated (README, docs/, Javadoc) where behavior changed
