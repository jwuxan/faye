---
description: Build a flow in the Admin UI's Blocks tab and block inspector, without writing YAML.
---

# Build a flow with blocks

This page explains the **Blocks** tab of the **Login flow** page and the block inspector beside it. It is for flow authors who build flows without writing YAML. Everything you do here changes the same draft as the **YAML** tab; see [Edit and publish a flow](flow-editor.md) for the rest of the workspace.

## The Blocks tab

The **Blocks** tab shows the draft as a tree of blocks. A block is one part of a flow: a built in credential such as **Password**, or a piece of logic such as **Choose**.

### Read the tree

The tree has two sections, each with a step count:

- **Sign in** holds the steps of the sign in, from the **Identifier** on. If the flow asks for consent, the **Consent** block and every step after it appear under a **Consent** heading. The heading says "runs after sign in, on the consent challenge". These steps run only after the sign in succeeds, when the authorization server asks for consent.
- **Approve** holds the steps that a phone runs to approve a QR code sign in ([ADR 0032](../adr/0032-delivered-one-time-codes-and-qr-sign-in.md)). The YAML has an `approve` section only when this section has steps.

Inside a section:

- A line joins steps that run in order.
- A **Choose** shows its options in a box, with "or" between them. The box heading says **The user picks one**, or names the hook that picks.
- A **Group** shows its steps under **In order**.
- Small chips under a block show what else the block does: its condition (`if`), its attempts, its settings, its hooks and a fail reason.
- A red or yellow count on a row is the number of errors or warnings on that block.

### Add a block

Select **Add block** in the toolbar, or press `/` anywhere outside a text field. Type to search, then press `Enter` or select a block. The blocks come in two groups.

| Group | Block | What it does |
|---|---|---|
| Steps | **Identifier** | Asks who is signing in, then finds the account. Always the first step. |
| Steps | **Password** | Verifies a password. |
| Steps | **Passkey** | Signs in with a passkey. |
| Steps | **Add passkey** | Offers to create a passkey, once signed in. |
| Steps | **One time code** | A code from an authenticator app, an email or a text message. You switch the kind in the inspector. |
| Steps | **Add phone** | Adds a phone number that the person proves with a texted code, once signed in. |
| Steps | **Enroll TOTP** | Sets up an authenticator app for time based one time passwords (TOTP). |
| Steps | **QR code** | Shows a QR code that the person scans and approves on their phone. |
| Steps | **Approve QR code** | On the phone: the person picks the number the other device shows. The last **Approve** step. |
| Steps | **Consent** | Asks to share data with the client. Runs after sign in, on the consent challenge. |
| Logic | **Choose** | Offers two or more credential blocks. The user or a hook picks one. |
| Logic | **Code by** | A **Choose** of one time codes: the authenticator app if enrolled, a texted code if phone codes are on, and an emailed code if the email is verified. The person picks. |
| Logic | **Group** | Runs nested steps in order, for example under one condition. |
| Logic | **Set** | Writes a value into `vars`. |
| Logic | **Fail** | Ends the sign in with a reason. |

The line at the bottom of **Add block** says where the block goes:

- If you selected a list heading, such as a section title or **The user picks one**, the block goes to the end of that list. Select a heading to add blocks inside a **Choose** or a **Group** without dragging.
- If you selected a block, the new block goes after the selected block.
- Otherwise, the block goes to the end of **Sign in**, before **Consent**.

The **Identifier** always goes first in **Sign in**, and **Approve QR code** always goes to the end of **Approve**.

### Why a block is disabled

**Add block** never offers a block that breaks the flow. A disabled block shows the reason under its name. These are the reasons the console gives:

| Reason | What to do |
|---|---|
| Start with the Identifier | The section has no Identifier yet. Add the **Identifier** first. |
| The flow already starts with the Identifier | The section has its Identifier. A section has only one. |
| An option is one credential block, or Fail | A **Choose** option cannot be a **Group**, **Set** or another **Choose**. |
| Consent is a step of its own | Add **Consent** as a step, not inside a **Choose** or **Group**. |
| Consent comes after every credential step | Move **Consent** after the last credential. |
| Steps after Consent run once signed in; put credentials before it | Select a step before **Consent**. |
| The Identifier already asks for the password | The **Identifier** uses **With password**, so no **Password** block is needed. |
| Approve QR code goes in the Approve section | Select the **Approve** heading first. |
| The Approve section asks for no consent | Add **Consent** to **Sign in** only. |
| Disallowed in General settings | The tenant's **Handlers** or **Screens** allowlist leaves this block out. See [General settings](tenant-settings.md#general-settings). |
| No email delivery for this tenant (or no text message delivery) | The operator has not set where codes go: `faye.tenants.<id>.delivery.email` or `.sms`. |

### Drag and drop

Drag a block by its row to move it. While you drag, gaps open only where the block can go and the flow still compiles. So the console never refuses a drop. You can also drag a block into a **Choose** or a **Group**.

### Use the keyboard

Click the tree once to give it focus. Then:

| Keys | What it does |
|---|---|
| `↑` `↓` | Select the block above or below. |
| `⌘↑` `⌘↓` | Move the selected block up or down, where it may go. |
| `⌫` or `Delete` | Delete the selected block. |
| `Esc` | Clear the selection. |
| `/` | Open **Add block**. |
| `⌘Z`, `⇧⌘Z` | Undo, redo. The console keeps 100 steps. |

When you delete a block, a message says **Deleted** with the block's id and offers **Undo**. If you delete an option and its **Choose** keeps only one option, that option becomes the step. It keeps the condition and hooks of the **Choose**.

## Edit a block in the inspector

Select a block in the tree or in the graph. The floating panel shows **Block** and opens the inspector. To close the panel, select **Hide panel**. To open the panel again, select **Block** or **Flow** at the top right of the graph.

The inspector shows only the settings that the selected block takes ([ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md)).

### Step ID, move and delete

The top row holds the block's **Step ID**, or **Option ID** for an option of a **Choose**. Type a new id and press `Enter`. An id starts with a letter and uses `a` to `z`, `0` to `9`, `-` and `_`. Step ids are unique across the flow. Option ids are unique within their **Choose**.

**Move up**, **Move down** and **Delete** are on the same row. A button is turned off when the move or delete breaks the flow. The button's label says why. For example, you cannot delete the **Identifier**.

If the checker found problems in this block, the problems appear under the top row.

### Condition

Every block except the **Identifier** can have a condition. Turn on **Only if** to add one. Without a condition, the block always runs. With a condition, the engine skips a step that fails the condition. The engine does not offer an option that fails the condition.

The condition builder has a list of tests:

1. Choose how the tests combine: **all**, **any** or **none** of them must be true.
2. In each test, type or pick a path, such as `user.has_totp` or `vars.risk`. The field suggests common paths and the `vars` names that **Set** blocks write.
3. Pick an operator, and a value if the operator needs one.

| Operator | Is true when |
|---|---|
| **is true** | The path is true. |
| **is false** | The path is not true. |
| **=** | The path equals the value. |
| **≠** | The path does not equal the value. |
| **contains** | The list at the path contains the value. |
| **excludes** | The list at the path does not contain the value. |
| **>** | The path is greater than the value, an integer. |
| **<** | The path is less than the value, an integer. |
| **is set** | The path has a value. |
| **is not set** | The path has no value. |

Select **Add condition** for another test, or **Add group** for a nested group with its own **all**, **any** or **none**. The line under the builder shows the whole condition as text. The builder writes the same condition that you write in YAML. For example, "**all** of `vars.risk` **=** `high`" is `if: { eq: [vars.risk, high] }`. The [Flow DSL reference](../reference/dsl.md#path-namespaces) lists every path that a condition can read.

### Settings of each block

| Block | Settings |
|---|---|
| **Identifier** | **View**: **Identifier first** asks for the password on a second screen. **With password** asks for both on one screen. **Passkey autofill** offers saved passkeys in the identifier field, and is unavailable with password. **Attempts** appears only with password. |
| **Password**, **Passkey**, **One time code**, **Enroll TOTP**, **QR code**, **Add phone** | **Attempts**: how many tries the person gets before the step fails. The field says **default** or **tenant default** until you change it. |
| **One time code** | **Kind**: **Authenticator app**, **Email** or **Text message**. A kind is off when the tenant has no delivery for it. |
| **Add passkey**, **Add phone** | **Required**: when on, the person must enroll. When off, the person can skip with **Not now** ([ADR 0038](../adr/0038-required-enrollment.md)). |
| Blocks that show a screen | **Path**: the URL path where the Login UI shows the block's screen. Leave it empty for the default ([ADR 0029](../adr/0029-screen-paths.md)). |
| **Choose** | **Picked by**: **User** or **Hook**. With **Hook**, choose the hook in **Picker**. **Hook** is turned off until the flow has a hook. A **Choose** needs two or more options. |
| **Set** | **Vars**: name and value pairs. Select **Add var** for another. |
| **Fail** | **Reason**: the failure reason, an id such as `denied`. |
| **Group** | No settings. Add steps under it in the tree. |

If you choose **With password** while the flow also has a **Password** block, the inspector warns that the block "asks for the password again". It also offers **Remove**.

YAML that you write by hand can give a block a key that the block does not take. The inspector then shows an **Unsupported keys** group, with the reason and a **Remove** button for each key. The draft fails the check until you remove these keys.

### Hooks

The **Hooks** group attaches the flow's hooks to the block:

- **Before** runs the hook once before the block, before all of its attempts.
- **After** runs the hook once after the block has run. A **Fail** block has no **After**, because nothing runs after it.

A hook can set `vars`, fail the step, or pick an option of a **Choose**. It cannot complete a sign in. To write a hook, go to the **Hooks** page. See [Hooks](../flows/hooks.md).

## Next steps

- Check, preview and publish the draft: [Edit and publish a flow](flow-editor.md).
- Try the draft with a real application: [Preview a draft](preview.md).
- See every block and setting: [Flow DSL reference](../reference/dsl.md#blocks).
