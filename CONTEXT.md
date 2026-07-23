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

**Area Click Action**:
An action that executes a finite sequence of clicks inside a confirmed polygon.
_Avoid_: Repeated Click, Multi-click

**Click Area**:
The simple polygon that bounds every generated point of an Area Click Action.
_Avoid_: Detection Area, Condition-relative Area

**Random Distribution**:
An Area Click Action distribution where every point is sampled uniformly and independently.

**Distributed Distribution**:
An Area Click Action distribution that uses best effort to spread sampled points spatially.

**Click Count**:
The exact number of clicks, from 1 through 50, executed by one Area Click Action trigger.

**Click Interval**:
The temporal wait after one click is released and before the next click begins.

**Image Condition**:
A screen condition evaluated against an ordered list of equivalent Reference Images. Presence succeeds when any Reference Image is detected; absence succeeds only when every Reference Image is valid and none is detected.

**Reference Image**:
One image entry belonging to an Image Condition. It owns its bitmap crop, native size, and Exact Search position while sharing threshold, visibility, and search mode with its Image Condition.
_Avoid_: Original Image, Main Image, Principal Image, Variation

## Relationships

- A **Zoom Action** has exactly one **Zoom Center** and one **Zoom Intensity**
- A **Zoom Action** has exactly one direction: **Zoom In** or **Zoom Out**
- **Zoom In** and **Zoom Out** name the visual result, not the finger movement
- An **Area Click Action** has one **Click Area**, one **Click Count**, and one distribution
- **Random Distribution** and **Distributed Distribution** control spatial placement only
- **Click Interval** applies only between clicks, never after the final click
- An **Image Condition** has 1 through 20 ordered **Reference Images**
- **Reference Images** are equivalent candidates; list order controls detection order, not hierarchy
- Exact Search uses each **Reference Image** position; Whole Screen and In Area searches use the **Image Condition** area

## Example dialogue

> **Dev:** "Does **Zoom In** mean that the simulated fingers move inward?"
> **Domain expert:** "No. It means the content grows visually around the **Zoom Center**."

## Flagged ambiguities

- "pinch in" and "pinch out" can describe either finger motion or visual result — resolved: use **Zoom In** and **Zoom Out** exclusively for the visual result.
- "distribution" can be confused with timing — resolved: distribution means spatial spacing; **Click Interval** is the temporal wait after release.
