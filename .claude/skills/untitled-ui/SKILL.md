---
name: untitled-ui
description: UI design language for ERGO Connect, distilled from the Untitled UI React starter kit and mapped onto our MUI frontend. Use when designing or restyling any screen, form, table, badge, modal, navigation or dashboard in ergo-connect-frontend/.
---

# ERGO Connect UI skill (Untitled UI style, built with MUI)

Source: the open-source Untitled UI Vite starter kit (https://github.com/untitleduico/untitledui-vite-starter-kit, MIT). Its conventions are summarised below. The kit itself is **not** kept in this repo. Re-clone it into a scratch folder if you need the exact component source.

## Stack rule

The kit is Tailwind v4 + React Aria. **Our frontend is MUI 9 + Redux Toolkit/RTK Query** (`ergo-connect-frontend/`, theme in `src/styles/theme.ts`).

- Do not add Tailwind, React Aria, `@untitledui/*` or any PRO package unless the user decides to migrate (log it in `ARCHITECTURE.md` §8 first).
- Recreate the look with MUI: `createTheme` overrides first (`palette`, `typography`, `shape`, `components`), then shared components (`src/components/ui`, `src/shared/components`), then feature screens. Avoid one-off `sx` styling.
- Keep ERGO's brand blue (`#0a369d`). Do not adopt the kit's purple.

## Kit principles to follow

1. **Semantic tokens, never raw colours.** Style by role (text-primary, bg-secondary, border-secondary), not by shade. Map them to `palette.text.*`, `palette.background.*`, `palette.divider` and `palette.{primary,success,warning,error}`, so a future dark mode needs only the theme.
2. **Size scale** `xs / sm / md / lg / xl` on every component (default Button `sm`, Input `md`). One consistent set of state props: disabled, loading, invalid, required.
3. **Compound, accessible components.** Labels tied to inputs, visible focus ring, full keyboard support.
4. **Disabled = 50% opacity**, with no separate disabled colours.
5. **Small transitions**: 100 ms, linear, for hover and colour changes.
6. **Icons**: line style at 16 / 20 / 24 px, decorative icons `aria-hidden`. Use `@mui/icons-material` Outlined variants.
7. **Link-styled actions** are Buttons with a link variant, not a separate Link component.
8. **Naming**: our repo uses PascalCase component files. Keep that. The kit's kebab-case rule applies to the kit only.

## Semantic colour roles (what each is for)

**Text**
- primary: page headings, key values
- secondary: labels, section headings
- tertiary: supporting and paragraph text
- quaternary: subtle text such as footer headings
- placeholder: input placeholders (gray-500 for contrast)
- brand: accents and metric numbers
- error / warning / success: semantic text, e.g. field errors

**Border**
- primary: inputs, button groups, checkboxes (high contrast)
- secondary: default for cards, tables, dividers (most common)
- tertiary: very subtle lines such as chart axes
- brand: focus and active states
- error: invalid fields

**Foreground** (icons and other non-text)
- primary and secondary: strong icons
- tertiary: medium icons
- quaternary: low-contrast icons, help icons, icons inside inputs
- brand, error, warning, success: featured icons, dots, progress

**Background**
- primary: white surfaces
- primary hover: dropdown and list item hover
- secondary: page and section contrast, hover and active nav items
- tertiary / quaternary: toggles, sliders, progress tracks
- active: selected menu item
- overlay: modal scrim
- brand primary / secondary: tints for check icons and featured icons
- brand solid (+ hover): primary buttons, toggles
- error / warning / success primary and secondary: tinted banners and badges; solid for strong emphasis

## Visual spec (MUI mapping)

| Aspect | Spec | MUI |
|---|---|---|
| Font | Inter, 14 px body / 20 px line height; 12 px captions; headings 18 / 20 / 24 px weight 600 | `typography.fontFamily`, `fontSize: 14` |
| Radius | 8 px buttons and inputs, 12 px cards, pill badges | `shape.borderRadius: 8` |
| Shadow | `xs` on inputs and buttons, `sm` on cards, nothing heavier on tables | `MuiPaper` / `MuiButton` overrides |
| Neutrals | text 900 / 700 / 600; borders 300 (inputs) and 200 (cards, tables); surfaces 50 | `palette.grey`, `palette.divider` |
| Buttons | primary (solid brand), secondary (outlined gray), tertiary (text), link, destructive variants; `disableElevation`, no uppercase, weight 600; icon leading / trailing; loading state keeps width | `MuiButton` defaultProps and variants |
| Fields | label above (weight 500), hint below, error text red, 1 px gray-300 border, brand focus ring (4 px brand-100), required marker, optional leading icon | `MuiOutlinedInput`, `MuiInputLabel`, `MuiFormHelperText` |
| Select / combo | item with optional supporting text, icon or avatar; search variant | `MuiAutocomplete` / `MuiSelect` |
| Badge | pill, optional dot, tinted bg + darker text (e.g. success-50 / success-700), sizes sm / md / lg, colours gray, brand, error, warning, success | `Chip size="small"` or a shared `StatusBadge` |
| Avatar | sizes xs–2xl, initials fallback, online / offline status dot | `MuiAvatar` |
| Featured icon | icon in a tinted circle or square (light, gradient, dark, outline) for empty states and modals | small shared component |
| Table | white card, gray-200 border, header row gray-50 with 12 px weight-500 gray-600 text, row dividers, row hover gray-50, footer pagination | `MuiTable*` overrides |
| Modal / slideout | centred modal with featured icon, title, description, footer actions; slideout for detail and edit | `MuiDialog`, `MuiDrawer` |
| Tabs | underline or pill; brand active | `MuiTabs` |
| Empty state | featured icon, title, supporting text, primary action | shared component |
| Navigation | simple sidebar and header with icon + label items, active item `bg-secondary` | existing dynamic Sidebar (see below) |
| Charts | brand + neutral palette, subtle axes (`border-tertiary`) | whatever chart lib the feature already uses |

## ERGO non-negotiables (visual changes must not break these)

- Render **only** the actions the server returns for a record (`CompanyAction`-style lists). Never guess permissions in the client.
- Never show competitor quotes, target prices, ERGO margins (`*_hidden`), internal notes or internal scores to external roles, even in a "richer" layout.
- Keep the server-driven sidebar (`docs/PERMISSIONS.md`). Restyle it, don't hard-code items.
- Module at NV shows nothing. Module at V is read-only: no write buttons.
- Status badges must come from the module's workflow states (SRS §14). Reopen, reject and send-back dialogs require a reason field.
- Money cells always show their currency (INR / USD / EUR / RMB).

## Workflow

1. Find the shared building block for the screen (`src/components/ui`, `src/shared/components`, feature folder).
2. Adjust the theme first, then shared components, then features.
3. Check states: hover, focus, disabled, invalid, loading, empty, error.
4. Run `npm run lint && npm run build` from `ergo-connect-frontend/`. If you can, check the screen at phone and desktop widths.
5. If asked to adopt the real kit (Tailwind + React Aria), stop and raise it as a migration decision covering scope, bundle size and MUI coexistence.

## Licence

The kit is MIT. PRO components and `@untitledui-pro/icons` are under a separate licence and must not be added.
