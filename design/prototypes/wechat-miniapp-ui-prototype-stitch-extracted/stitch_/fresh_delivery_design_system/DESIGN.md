---
name: Fresh Delivery Design System
colors:
  surface: '#f8f9fb'
  surface-dim: '#d9dadc'
  surface-bright: '#f8f9fb'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f2f4f6'
  surface-container: '#edeef0'
  surface-container-high: '#e7e8ea'
  surface-container-highest: '#e1e2e4'
  on-surface: '#191c1e'
  on-surface-variant: '#3d4a3f'
  inverse-surface: '#2e3132'
  inverse-on-surface: '#f0f1f3'
  outline: '#6d7a6e'
  outline-variant: '#bccabc'
  surface-tint: '#006d37'
  primary: '#006d37'
  on-primary: '#ffffff'
  primary-container: '#27ae60'
  on-primary-container: '#00391a'
  inverse-primary: '#61de8a'
  secondary: '#546346'
  on-secondary: '#ffffff'
  secondary-container: '#d7e8c3'
  on-secondary-container: '#5a694c'
  tertiary: '#596055'
  on-tertiary: '#ffffff'
  tertiary-container: '#949b8e'
  on-tertiary-container: '#2c3329'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#7efba4'
  primary-fixed-dim: '#61de8a'
  on-primary-fixed: '#00210c'
  on-primary-fixed-variant: '#005228'
  secondary-fixed: '#d7e8c3'
  secondary-fixed-dim: '#bbcca9'
  on-secondary-fixed: '#121f08'
  on-secondary-fixed-variant: '#3d4b30'
  tertiary-fixed: '#dee5d6'
  tertiary-fixed-dim: '#c2c9bb'
  on-tertiary-fixed: '#171d14'
  on-tertiary-fixed-variant: '#42493e'
  background: '#f8f9fb'
  on-background: '#191c1e'
  surface-variant: '#e1e2e4'
typography:
  display-price:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 26px
  body-lg:
    fontFamily: Be Vietnam Pro
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Be Vietnam Pro
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 22px
  label-sm:
    fontFamily: Be Vietnam Pro
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
  headline-lg-mobile:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  container-margin: 12px
  gutter-md: 8px
  section-gap: 20px
  card-padding: 12px
  touch-target-min: 44px
---

## Brand & Style

The design system is centered on the concept of "Freshness at Speed." It targets health-conscious urban dwellers in China who value quality, transparency, and efficiency. The UI evokes a sense of walking through a high-end organic market: airy, vibrant, and meticulously organized.

The aesthetic follows a **Modern Minimalism** approach with **Tactile** influences. It utilizes generous whitespace to let high-quality food photography shine, paired with soft shadows and rounded geometry to create a friendly, trustworthy atmosphere. The interface is intentionally "quiet" to reduce cognitive load during the daily grocery shopping routine, ensuring that the vibrant colors of fresh produce remain the focal point.

## Colors

The palette is rooted in nature. The **Primary Green (#27AE60)** is a slightly more saturated, professional green than standard leaf colors, ensuring high legibility for call-to-action buttons. 

- **Primary**: Used for main actions, category highlights, and price tags.
- **Secondary (Mint)**: Used for subtle backgrounds, tag backgrounds, and progress indicators.
- **Neutral**: A cool-toned light grey-white (#F7F8FA) serves as the canvas, preventing the "dirty" look of pure white on mobile screens.
- **Semantic Accent**: A vibrant **Orange** is used sparingly for promotional badges (e.g., "Limited Time") and notification dots to provide high-contrast visual cues without overwhelming the green brand identity.

## Typography

For the WeChat Mini Program environment, the system defaults to **PingFang SC** for Chinese characters to ensure native rendering. For numerals and English text, **Plus Jakarta Sans** provides a modern, rounded geometric feel that matches the friendly brand persona.

- **Prices**: Use a bold weight and larger scale. Always prefix with the '¥' symbol in a slightly smaller font size than the value.
- **Product Names**: Use `body-lg` with a medium weight (500) to ensure readability against product images.
- **Secondary Info**: Use `label-sm` in a medium grey for weights (e.g., "500g/份") and origin info.
- **Chinese Optimization**: Maintain a minimum line height of 1.5x for Chinese text to prevent visual crowding of complex glyphs.

## Layout & Spacing

This design system uses a **Fluid Grid** model optimized for narrow mobile viewports. 

- **Margins**: A standard 12px margin on the left and right of the screen ensures content doesn't feel cramped while maximizing horizontal space for product listings.
- **Product Grids**: Use a 2-column layout for "Discovery" feeds with an 8px gutter. For "Search Results," use a single-column list format to emphasize details like weight and delivery time.
- **Visual Rhythm**: Use increments of 4px for all spacing. Standard vertical gaps between logical sections (e.g., Hero Banner to Category Grid) should be 20px.

## Elevation & Depth

Hierarchy is achieved through **Tonal Layering** and soft, ambient shadows. 

- **Level 0 (Background)**: #F7F8FA.
- **Level 1 (Cards/Surface)**: Pure white (#FFFFFF) with a very soft shadow (0px 4px 12px rgba(0,0,0,0.04)). This is the primary container for product information.
- **Level 2 (Floating/Interactive)**: Used for the "Add to Cart" sticky bar and floating "Back to Top" buttons. These use a more pronounced shadow (0px 8px 20px rgba(0,0,0,0.08)) to indicate they sit above the scrollable content.
- **Depth Cues**: Use 1px borders in a very light grey (#F0F0F0) only when a shadow feels too heavy, such as for internal dividers within a card.

## Shapes

The shape language is organic and approachable. 

- **Product Cards**: 12px corner radius is the standard.
- **Action Buttons**: Use a "Semi-Pill" shape (100px radius or height/2) for primary actions like "Pay Now" or "Add to Cart."
- **Input Fields**: 8px radius for search bars and quantity selectors.
- **Image Containers**: Always match the corner radius of their parent card or use a consistent 8px for standalone product thumbnails.

## Components

- **Buttons**: Primary buttons are solid Fresh Green with white text. Ghost buttons use a Green outline with a light mint hover state.
- **Search Bar**: A full-width bar with a 1px border and a subtle light green tint (#F1F8E9) inside the field to signify it as the primary entry point.
- **Quantity Selector**: A minimal horizontal component. The "plus" button is the primary green, while the "minus" and "count" are neutral to focus the user on adding items.
- **Chips/Tags**: Small, low-height labels for "Freshness Guarantee" or "Organic" using `label-sm`. They should have a 4px radius and use the `tertiary_color` (Light Mint) as a background.
- **Bottom Tab Bar**: Follows WeChat standard height but uses outline-style icons that transition to solid green when active. 
- **Product Cards**: A vertical stack featuring a top-aligned image, title, weight subtitle, price, and a floating green "+" button in the bottom right corner for quick-add.