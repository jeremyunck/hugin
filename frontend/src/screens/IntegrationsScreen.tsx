// The integrations UI is a thin presentational panel; the screen is its component re-export so the
// screens/ directory holds one entry per top-level screen. State and actions live in
// hooks/useIntegrations.
export { IntegrationPanel as IntegrationsScreen } from "../components/IntegrationPanel";
