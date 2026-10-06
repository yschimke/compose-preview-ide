# Compose Preview IDE

Read README.md before changing the build or release boundary.

- Use `agent/...` branches and Conventional Commits. Open a PR; never auto-merge.
- Commits use the human identity Yuri Schimke <yuri@schimke.ee>, with no AI authorship trailers.
- Run `./gradlew ktfmtFormat` before committing Kotlin changes, then `./gradlew check buildPlugin`.
- Fetch main and check that the branch/PR has not merged immediately before every push.
- The editor and JVM host stay in compose-ui-builder. Do not copy their source into this repository.
- Keep the plugin ID and Compose/Skiko exclusions stable. The IDE supplies those runtime classes.
- The default source dependency must remain a full commit SHA. Release jobs must use that pin.
