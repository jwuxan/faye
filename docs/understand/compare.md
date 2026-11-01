---
description: How Faye's approach to sign in flows compares with Keycloak, Auth0, Okta, Azure AD B2C, AWS Cognito, Ory and authentik, including where those products are stronger.
---

# How Faye compares

This page compares how Faye and other identity products let you shape a sign in. It is for engineers who are choosing a design, or who know one of these products and want to place Faye.

It compares designs, not speed. No performance benchmark of Faye exists, and this page makes no performance claim. Each statement about another product links to that product's documentation or source code. Products change: the facts were checked on 2026-11-01. If you find one that is out of date, please open an issue.

## The short version

- **Faye checks every path of a flow before it runs.** It refuses a flow if any path can succeed without verifying a credential. We found no equivalent in the other products. The closest are tools that simulate one request or step through one live run.
- **In Faye, custom code cannot decide who signed in.** A hook can only set values, fail a step, or pick an option. In most other products, custom code can verify a credential or end the sign in successfully.
- **In Faye, assurance is computed.** `amr` and `acr` come from the credentials that verified. Elsewhere they are often configured by an administrator, or asserted by a login app.
- **Faye stores a flow as a small, versioned IR**, so that the way flows are written can change and a flow can run on another authorization server. Other products also export their configuration as files, so portability is close to a tie today: Hydra is still Faye's only real host.
- **The other products are far more mature and do far more.** They run in production at scale, and they ship sign up, account recovery, federation, user management and logout. Faye is a prototype that does none of those yet.

## Four questions that separate the designs

### 1. Where does the sign in logic live?

| Product | Where the flow is defined |
|---|---|
| **Faye** | A closed YAML language, compiled to a versioned JSON IR. Each version is stored by its hash. |
| **Keycloak** | Authentication flows of executions and sub flows, each marked Required, Alternative, Conditional or Disabled, edited in the admin console and exported as realm JSON ([flows](https://github.com/keycloak/keycloak/blob/main/docs/documentation/server_admin/topics/authentication/flows.adoc), [export](https://github.com/keycloak/keycloak/blob/main/docs/guides/server/importExport.adoc)). |
| **Auth0** | Universal Login, plus Actions in Node.js that run after the first factor, plus Forms. The Deploy CLI exports a tenant as YAML or JSON ([deploy CLI](https://auth0.com/docs/deploy/deploy-cli-tool/deploy-cli-tool-options)). |
| **Okta** | Authentication policies with if and then rules, in the admin console, the API or Terraform ([Terraform](https://developer.okta.com/docs/guides/terraform-design-access-security/main/)). |
| **Azure AD B2C** | Custom policies in XML: user journeys of orchestration steps and technical profiles ([user journeys](https://learn.microsoft.com/en-us/azure/active-directory-b2c/userjourneys)). |
| **AWS Cognito** | Fixed flows plus Lambda functions at set points, the triggers ([define auth challenge](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-lambda-define-auth-challenge.html)). |
| **Ory Kratos** | Fixed self service flows configured in YAML, with a JSON identity schema and webhooks ([hooks](https://github.com/ory/docs/blob/master/docs/kratos/hooks/01_configure-hooks.mdx)). |
| **authentik** | Flows of stages bound to policies, exported as YAML blueprints ([flows](https://github.com/goauthentik/authentik/blob/main/website/docs/add-secure-apps/flows-stages/flow/index.mdx)). |

### 2. Can custom code decide who signed in?

This is the most important difference. If custom code can end a sign in successfully, every review of the flow must also review the code.

| Product | What custom code can do |
|---|---|
| **Faye** | A hook runs in a WebAssembly sandbox and returns one of four results: nothing, set values, fail, or pick an option. It never sees or writes the authentication state. Only built in handlers verify credentials. |
| **Keycloak** | A custom authenticator is Java code that calls `context.setUser(...)` and `context.success()`, so it decides the outcome outright ([Authenticator SPI](https://github.com/keycloak/keycloak/blob/main/docs/documentation/server_development/topics/auth-spi.adoc)). |
| **Auth0** | Post login Actions can deny access, and `api.authentication.challengeWith` overrides the tenant's MFA policy ([post login API](https://auth0.com/docs/customize/actions/explore-triggers/signup-and-login-triggers/login-trigger/post-login-api-object)). A custom database login script verifies the password in your code ([login script](https://auth0.com/docs/authenticate/database-connections/custom-db/templates/login.md)). |
| **Okta** | Inline hooks call your HTTP service during a flow. The password import hook answers whether the password is `VERIFIED` ([password import](https://developer.okta.com/docs/guides/migrate-to-okta-password-hooks/main)). |
| **Azure AD B2C** | Claims transformations and REST technical profiles inside the policy decide what the journey does and which claims it emits ([JWT issuer](https://learn.microsoft.com/en-ca/azure/active-directory-b2c/jwt-issuer-technical-profile)). |
| **AWS Cognito** | In a custom authentication flow, the Define Auth Challenge Lambda returns `issueTokens: true` to end the sign in successfully ([define auth challenge](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-lambda-define-auth-challenge.html)). |
| **Ory Kratos** | Webhooks can stop a flow or change the identity, in registration and settings flows ([webhooks](https://github.com/ory/docs/blob/master/docs/guides/integrate-with-ory-cloud-through-webhooks.mdx)). |
| **authentik** | Expression policies are Python that runs on the server. Its docs call editing them "a highly privileged permission", because a policy can change how the rest of the flow behaves ([expression policies](https://github.com/goauthentik/authentik/blob/main/website/docs/customize/policies/types/expression/index.mdx)). |

Faye gives up flexibility to get this property. A hook cannot add a new kind of credential. That takes a new built in handler, reviewed once for every tenant.

### 3. Is a flow checked before it runs?

| Product | Checks before a flow runs |
|---|---|
| **Faye** | The checker lists every path of every entry and refuses the flow if a path can succeed without a credential, if a tenant that requires two factors has a one factor path, and on other rules. It runs at publish, rollback, preview and startup. |
| **Keycloak** | We found none. Its docs note that executions in a sub flow that is not marked Conditional are not evaluated, and count as disabled, without a warning ([flows](https://github.com/keycloak/keycloak/blob/main/docs/documentation/server_admin/topics/authentication/flows.adoc)). |
| **Okta** | The Access Testing Tool and the policy simulation API show which rules match one simulated request ([Access Testing Tool](https://help.okta.com/oie/en-us/Content/Topics/identity-engine/policies/access-testing-tool.htm)). They check one request, not every path. |
| **authentik** | The Flow Inspector steps through one live run of a flow ([inspector](https://github.com/goauthentik/authentik/blob/main/website/docs/add-secure-apps/flows-stages/flow/inspector.mdx)). |
| **Auth0, Azure AD B2C, AWS Cognito, Ory Kratos** | We found no check of every path. Errors appear when a flow runs. |

Faye can list every path because its flow language is closed and every flow ends. A product whose flows can call any code cannot list every path the code might take. [How the IR is designed](ir-design.md#what-the-checker-can-prove) explains how Faye does it.

### 4. Where do amr and acr come from?

`amr` says which methods verified, and `acr` says how strong the sign in was. Applications use them to decide whether to trust a sign in.

| Product | Source of `amr` and `acr` |
|---|---|
| **Faye** | Computed by the engine from the credentials that verified. Two factors of different kinds give `faye:2fa`. No setting, flow or hook can write them. |
| **Keycloak** | Configured. An administrator assigns a level of authentication to each conditional sub flow and an `amr` value to each execution. Its docs say: "It's up to the admin to make sure that the LoA is coherent" ([flows](https://github.com/keycloak/keycloak/blob/main/docs/documentation/server_admin/topics/authentication/flows.adoc)). |
| **Auth0** | Set by Auth0, and protected from custom claims ([custom claims](https://auth0.com/docs/secure/tokens/json-web-tokens/create-custom-claims)). Step up is written as Action code that checks `acr_values` ([step up](https://auth0.com/docs/secure/multi-factor-authentication/step-up-authentication/configure-step-up-authentication-for-web-apps)). |
| **Okta** | Emitted by Okta from the authenticators used. Applications ask for levels such as `urn:okta:loa:2fa:any` ([step up](https://developer.okta.com/docs/guides/step-up-authentication/main/)). |
| **Azure AD B2C** | `acr` is the policy's name by default, and `amr` is not issued unless the policy outputs it ([JWT issuer](https://learn.microsoft.com/en-ca/azure/active-directory-b2c/jwt-issuer-technical-profile)). |
| **AWS Cognito** | Set by Cognito. The pre token generation Lambda cannot change them ([pre token generation](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-lambda-pre-token-generation.html)). |
| **Ory Kratos** | Kratos computes an assurance level, `aal1` or `aal2`, from the methods used ([sessions](https://github.com/ory/docs/blob/master/docs/kratos/session-management/01_overview.mdx)). |
| **Ory Hydra** | Taken from the login app. The login app passes `subject`, `acr` and `amr` when it accepts the login ([login flow](https://github.com/ory/docs/blob/master/docs/oauth2-oidc/custom-login-consent/flow.mdx)). |

Auth0, Okta, Cognito and Kratos also keep these values out of the reach of custom code. Faye's difference is that it derives `acr` from `amr` by a fixed rule, and refuses at publish time a flow whose paths cannot meet the tenant's policy.

## Faye and Ory Hydra

Hydra and Faye are designed to work together. Hydra handles OAuth2 and OpenID Connect and deliberately ships no login screens. It hands each login to a login app, and it trusts what that app reports: the subject, `amr` and `acr`.

So Hydra's safety depends on the login app. A login app with one wrong branch can sign anyone in, and Hydra has no way to know. Faye is that login app, built so that the wrong branch cannot be published: the checker refuses it, and the engine computes `amr` and `acr` instead of trusting a flow. See [Architecture](architecture.md).

## Other differences

| | Faye | Most others |
|---|---|---|
| **Screens** | Typed screens that a tenant can brand but not redesign | Hosted pages with themes or templates, and often a way to build your own UI, such as Auth0's custom login screens, Okta's embedded widget or Kratos's bring your own UI |
| **Preview** | A draft runs with a real application before it is published, and the run is compared with the checker's paths | Azure AD B2C runs a policy that is already uploaded ("Run now"). authentik's inspector runs a live flow. Auth0 versions single Actions, not a whole login. |
| **Versions** | Every published flow is kept by its hash. A sign in keeps the version it started with. Roll back to any version. | Varies. Many products overwrite the current configuration. |
| **Lock in** | The IR is designed to run on other authorization servers. Not proven yet. | Each product's flow format runs only on that product. |

## Where the others are stronger

Faye is a prototype. Be clear about what it does not do before you choose it.

- **Maturity and scale.** Every product on this page runs in production, most with high availability, several regions, rate limiting and years of security review. Faye runs on one SQLite file, with no high availability and no rate limiting at scale.
- **Scope.** The others ship sign up, account recovery, user management and logout. Most also federate with social and enterprise identity providers. Faye has none of these yet ([open gaps](../adr/0036-retire-the-roadmap.md#open-gaps)).
- **Ecosystem.** Keycloak's providers, the Auth0 marketplace and the Okta Integration Network offer ready integrations. Faye has none.
- **UI freedom.** If you need fully custom sign in pages, products that let you bring your own UI fit better. Faye limits tenants to typed screens on purpose.

## When Faye fits

Faye fits when:

- you run, or want to run, a headless authorization server such as Ory Hydra, and need its login and consent app;
- you want sign in flows as reviewable data, with a machine check that no path skips a credential;
- you want tenants or product teams to change flows without writing login code, and without the power to weaken them;
- you can work with a prototype, or want to help take it to production.

Faye does not fit, yet, when you need a complete identity product with sign up, recovery, federation and production hardening today.

## Next steps

- Why the IR is the contract, not the YAML: [How the IR is designed](ir-design.md).
- The guarantees in detail: [Security model](security.md).
- What it takes to run Faye on another authorization server: [Embed Faye: the host SPI](host-spi.md).
