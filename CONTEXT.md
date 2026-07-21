# Klick'r Automation

Language for describing automated scenarios and their user-visible actions.

## Language

**Zoom Action**:
An action that changes the apparent scale of content around a chosen center.
_Avoid_: Pinch Action, Scale Action

**Zoom In**:
The Zoom Action direction that enlarges content visually.
_Avoid_: Pinch Out

**Zoom Out**:
The Zoom Action direction that reduces content visually.
_Avoid_: Pinch In

**Zoom Center**:
The screen location around which a Zoom Action changes scale.
_Avoid_: Pinch Location, Gesture Position

**Zoom Intensity**:
The distance traveled by each simulated finger during a Zoom Action.
_Avoid_: Pinch Amount, Zoom Quantity

## Relationships

- A **Zoom Action** has exactly one **Zoom Center** and one **Zoom Intensity**
- A **Zoom Action** has exactly one direction: **Zoom In** or **Zoom Out**
- **Zoom In** and **Zoom Out** name the visual result, not the finger movement

## Example dialogue

> **Dev:** "Does **Zoom In** mean that the simulated fingers move inward?"
> **Domain expert:** "No. It means the content grows visually around the **Zoom Center**."

## Flagged ambiguities

- "pinch in" and "pinch out" can describe either finger motion or visual result — resolved: use **Zoom In** and **Zoom Out** exclusively for the visual result.
