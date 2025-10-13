# SMS Gateway Frontend

This directory contains the Vue.js (Version 3) and Vuetify (Version 3) frontend for the SMS Gateway application.

## Functionality

Currently, this frontend provides a user interface to:
- Compose and send an SMS message.

## Prerequisites

Before you can run this frontend locally, you need to have the following installed:
- [Node.js](https://nodejs.org/) (which includes npm - Node Package Manager). It's recommended to use a recent LTS version.
- Optionally, [yarn](https://yarnpkg.com/) if you prefer it over npm.

## Local Development Setup

1.  **Navigate to the frontend directory:**
    ```bash
    cd frontend
    ```

2.  **Install Dependencies:**
    Using npm:
    ```bash
    npm install
    ```
    Or using yarn:
    ```bash
    yarn install
    ```

3.  **Run the Development Server:**
    Using npm:
    ```bash
    npm run dev
    ```
    Or using yarn:
    ```bash
    yarn dev
    ```
    This will typically start a development server (powered by Vite) on `http://localhost:8081` (or the port specified in `vite.config.js`). The application will automatically reload if you make changes to the source files.

4.  **Access the Application:**
    Open your web browser and navigate to the URL provided by the Vite development server (e.g., `http://localhost:8081`).

## Project Structure

-   `public/`: Contains static assets that are copied directly to the build output.
-   `src/`: Contains the main source code for the Vue application.
    -   `assets/`: For static assets processed by Vite (e.g., images, fonts if not handled by Vuetify directly).
    -   `components/`: Contains reusable Vue components.
        -   `SendSmsForm.vue`: The component for composing and sending SMS messages.
    -   `App.vue`: The main root Vue component.
    -   `main.js`: The entry point of the application. Initializes Vue, Vuetify, Axios, etc.
-   `index.html`: The main HTML file that Vite uses to bootstrap the application.
-   `vite.config.js`: Configuration file for Vite (the build tool). Includes Vue and Vuetify plugin configurations and proxy settings for API calls during development.
-   `package.json`: Lists project dependencies and scripts.

## API Interaction

-   The frontend makes API calls to the backend service (assumed to be running on `http://localhost:8080` during development).
-   The `vite.config.js` file includes a proxy rule to forward requests from `/api` on the frontend server to `/api` on the backend server, avoiding CORS issues during local development.
-   The endpoint for sending SMS is `POST /api/sms/send`.
```
