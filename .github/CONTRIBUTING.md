# Contributing to Sendium

First off, thank you for considering contributing to Sendium! It's people like you that make the open-source community such a powerful place to learn, inspire, and create.

This document outlines the process for setting up your local environment and submitting your changes.

---

## 🛠 Prerequisites

Before you begin, ensure you have the following installed on your local machine:

* **Java:** JDK 25
* **Maven:** Use the provided Maven wrapper (`./mvnw`, or `.\mvnw.cmd` on Windows)
* **Git:** For version control

---

## 💻 Local Development Setup

Sendium is built on Quarkus, which makes local development incredibly fast and features live coding.

**1. Clone the repository:**
```bash
git clone https://github.com/cytechmobile/sendium.git
cd sendium
```
**2. Start the application in development mode:**
```bash
./mvnw -pl sendium-app -am quarkus:dev
```
Note: This will start the server with live reload enabled. Any changes you make to the Java code will automatically trigger a compilation and reload.

On Windows PowerShell, replace `./mvnw` with `.\mvnw.cmd`.

**3. 🧪 Testing**

We value reliability. Before submitting any changes, please ensure all tests pass.

You do not need Docker running locally to execute the test suite. Simply run the following command to execute all unit and integration tests:
```bash
./mvnw verify
```
**4. 💅 Code Style & Linting**

We enforce a consistent code style across the project using Checkstyle. Our rules are defined in the checkstyle.xml file located in the root of the repository.

Please ensure your IDE is configured to format code according to standard Java conventions (4 spaces for indentation, 160 character line limit, etc., as defined in the XML).

If your code violates the Checkstyle rules, the build will issue warnings or fail. You can manually check your code before committing by running:
```bash
./mvnw -pl sendium-core checkstyle:check
```
**5. 🚀 Pull Request Process**

When you are ready to submit your code, please open a Pull Request (PR).

To ensure a smooth review process, please adhere to the following rules (which are also outlined in our PR template):

a. Conventional Commits: 

Your PR title must follow the Conventional Commits specification (e.g., feat: add new SMPP routing logic or fix: resolve null pointer in HTTP webhook).

b. Link an Issue: 

If your PR solves an existing issue, link it in the PR description using the Closes #123 syntax. If no issue exists, consider opening one first to discuss the proposed changes.

c. Add Tests: 

If you are adding a new feature or fixing a bug, please include tests that prove your fix is effective or that your feature works.

d. Pass the Build:

Ensure `./mvnw verify` passes locally and all GitHub Actions (CI) checks pass after opening your PR.

We look forward to reviewing your contributions!
