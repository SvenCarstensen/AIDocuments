# Contributing to AIDocuments

Contributions are welcome – bug reports, ideas and pull requests alike.

## License of the project

AIDocuments is **source-available** under the [PolyForm Noncommercial License 1.0.0](LICENSE.md): you may read,
use, change and share the code for any noncommercial purpose. Commercial use requires a separate license from the
copyright holder.

## Contributor agreement

AIDocuments is also offered under commercial licenses. So that contributions can be part of every edition, each pull
request is accepted only under the following terms. By submitting a contribution (code, documentation, translations
or other material), you agree that:

1. You grant Sven Carstensen and anyone he licenses the project to a perpetual, worldwide, non-exclusive, royalty-free,
   irrevocable license to use, reproduce, modify, prepare derivative works of, publicly display, sublicense and
   distribute your contribution, under any license terms, including commercial ones.
2. You grant the same parties a perpetual, worldwide, non-exclusive, royalty-free, irrevocable patent license for any
   of your patent claims that your contribution necessarily infringes, alone or combined with the project.
3. The contribution is your own original work, or you otherwise have the right to submit it under these terms. If
   your employer has rights to what you create, you have their permission or they have waived those rights.
4. You keep the copyright to your contribution. You are not required to provide support for it, and it is provided
   "as is", without warranties of any kind.

Pull requests confirm this agreement with the checkbox in the pull request template.

## How to contribute

- Discuss larger changes in an issue first.
- Keep the user interface and all 36 translations in sync (`npm run check:locales` in `frontend/`).
- Run the tests before submitting: `cd backend && ./mvnw test` and `cd frontend && npm run build`.
- Never include real personal data (names, invoice numbers, addresses) in tests, examples or screenshots.
