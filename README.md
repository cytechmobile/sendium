# Sendium

## Overview

This project is an SMS Gateway that allows for sending SMS messages and dynamically routing them based on configurable rules. It includes a backend built with Java (Quarkus) and a frontend with Vue.js.

## Features

*   Send SMS messages via an API.
*   Manage SMS routing rules through a user-friendly web interface.
*   Dynamic loading of routing rules.

## Routing Rule Management

The SMS Gateway provides a web interface to manage routing rules, allowing users to control how incoming SMS messages are processed and directed.

## Development

### Backend
The backend is a Quarkus application.
* To run in development mode: `cd sms-gateway && ./mvnw quarkus:dev`
* API documentation (Swagger UI) is available at `/q/swagger-ui` when running.

### Frontend
The frontend is a Vue.js application built with Vite.
* To run in development mode: `cd frontend && npm install && npm run dev`
* The application will typically be available at `http://localhost:5173`.

### E2E Tests
The frontend includes End-to-End tests using Playwright.
* To run E2E tests:
  1. Ensure both backend and frontend dev servers are running.
  2. `cd frontend`
  3. `npm install` (if not already done)
  4. `npx playwright install` (if not already done)
  5. `npx playwright test`
  
  Tests are configured to run against `http://localhost:5173` by default.
