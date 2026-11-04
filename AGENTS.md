\# Project Instructions



\## Project Context

\- This is a Java 21 order management project.

\- The project uses a single Maven module with named domain packages.

\- Keep the monetary domain framework-independent; Spring Boot REST is planned for a later checkpoint.

\- Keep the implementation simple and maintainable.



\## Coding Standards

\- Use BigDecimal for monetary calculations.

\- Validate method inputs explicitly.

\- Prefer readable code over unnecessary abstractions.

\- Do not introduce external dependencies without approval.



\## Testing

\- Add or update tests when changing business logic.

\- Run tests after modifying Java code.

\- Compile production and test sources with Maven: `mvn -o test-compile`.

\- Execute the existing 58-scenario harness in PowerShell: `java -cp "target/classes;target/test-classes" com.example.orders.domain.OrderServiceTest`.

\- Maven compiles the harness; execute its main method explicitly to run the scenarios.

\- Keep Maven offline unless network access is explicitly approved.

\- Report test results accurately.

\- Never claim tests passed unless they were executed.



\## Safety Rules

\- Do not modify files outside this project.

\- Do not access the network unless explicitly authorized.

\- Do not delete files without approval.

\- Do not create Git commits unless explicitly requested.



\## Communication

\- Explain planned changes before implementing them.

\- Summarize modified files after implementation.

\- Clearly report any limitations or failed commands.
