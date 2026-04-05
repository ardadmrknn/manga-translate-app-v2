# Agent Behavior Rules for mangaceviriv2

1. **Strict Tool Usage:** Whenever a file modification is needed, invoke the `edit_file` or `create_file` tool immediately. Do not ask for confirmation for plan steps.
2. **Conciseness:** Skip lengthy explanations and "Chain of Thought" summaries unless specifically asked.
3. **Execution Mode:** Default to "Implementation Mode" (Uygulama Modu). If you identify a bug, fix it directly without a separate planning phase.
4. **Context Awareness:** Always check `AndroidManifest.xml` and `build.gradle.kts` before proposing code changes to ensure compatibility.
5. **No Repeats:** Never repeat the same plan after the user has said "Uygula" or "Execute".