# Contributing to Open Minecraft Server Infrastructure

Thank you for your interest in contributing to this project! This document provides guidelines for contributing.

## Development Process

1. **Fork the repository** and create your branch from `main`
2. **Make your changes** following the guidelines below
3. **Run local CI checks** using `./scripts/ci-local.sh`
4. **Test your changes** thoroughly
5. **Submit a pull request** with a clear description of your changes

## Code Standards

### Shell Scripts
- Use `#!/bin/bash` shebang for all shell scripts
- Follow ShellCheck recommendations
- Include proper error handling with `set -e` where appropriate
- Use meaningful variable names and include comments for complex logic

### Docker
- Keep Dockerfile efficient with multi-stage builds where appropriate
- Use specific base image versions for reproducibility
- Minimize the number of layers and image size
- Follow Docker best practices for security

### Documentation
- Update README.md for any user-facing changes
- Keep documentation clear and concise
- Include examples where helpful
- Ensure all links are valid

## Testing

### Required Checks
Before submitting a pull request, run the local CI validation script:

```bash
./scripts/ci-local.sh
```

It covers shell script syntax and ShellCheck linting, Docker Compose
configuration, the nginx configuration test, environment and documentation
checks, `helm lint`, `helm unittest`, `terraform fmt`/`validate` for all four
Terraform targets, the Gradle test suite for every module, and the Python
client's unit tests.

ShellCheck, Helm, the [helm-unittest](https://github.com/helm-unittest/helm-unittest)
plugin, Terraform, and a reachable Docker daemon are optional locally: any check
whose tool is unavailable is skipped with a warning and listed again in the
summary the script prints at the end. Those checks are still enforced by CI, so
installing the tools is the only way to catch those failures before pushing:

```bash
helm plugin install https://github.com/helm-unittest/helm-unittest --version v1.1.0
```

### CI Pipeline
The automated CI pipeline will run additional checks including:
- Security vulnerability scanning
- Docker configuration validation
- Integration testing

## Submitting Changes

### Pull Request Guidelines
- Use a clear and descriptive title
- Provide a detailed description of what changes were made and why
- Reference any related issues using `Fixes #123` or `Closes #123`
- Ensure all CI checks pass before requesting review

### Commit Messages
Use clear and descriptive commit messages:
- Start with a verb in the present tense (e.g., "Add", "Fix", "Update")
- Keep the first line under 50 characters
- Include additional details in the body if necessary

## Getting Help

If you have questions or need help:
- Check existing issues and pull requests
- Create a new issue with the question label
- Be specific about what you're trying to do and what problems you're encountering

## Code of Conduct

Please be respectful and professional in all interactions. We want to maintain a welcoming environment for all contributors.