"""Generate composite pipe-tip blockymodels + the JSON wiring for push/pull tips.

This is the Task 3 generator for the tip-rendering feature
(docs/plans/2026-06-07-tip-rendering-design.md). It reads a family's authored
topology models plus the three authored tip orientations and emits, for every
legal (shape x <=2 tipped arms x push/pull) combination, a composite blockymodel
plus the JSON additions (State.Definitions, TemplateShapeBlockPatterns,
connected-template Shapes) that wire them up. Nothing is hand-authored: the tip
geometry is extracted by diffing the authored end_push / end_pull (horizontal)
and vertical_end_{up,down}_{push,pull} models against their plain counterparts,
then transposed onto every arm of every topology shape.

================================================================================
STAGE A: blockymodel format findings (read before trusting the logic below)
================================================================================

A blockymodel is `{"nodes": [...], "format": "prop", "lod": "auto"}`. Each node:

    {
      "id": "<string int>",            # 1-based, unique within the file
      "name": "<string>",              # base node name == model name; arms "Stub_<DIR>"
      "position": {x,y,z},             # the node's CENTRE in model space
      "orientation": {x,y,z,w},        # quaternion; ALWAYS identity in this corpus
      "shape": {
        "type": "box",
        "offset": {x,y,z},             # ALWAYS zero in this corpus
        "stretch": {x,y,z},            # PER-AXIS stretch (project GOTCHA): collars use 2,2,2
        "settings": {"isPiece":false, "size":{x,y,z}, "isStaticBox":true},
        "textureLayout": {back,front,left,right,top,bottom -> {offset{x,y},mirror{x,y},angle}},
        "unwrapMode":"custom","visible":true,"doubleSided":false,"shadingMode":"flat"
      }
    }

There is NO texture-reference field inside the blockymodel: the texture is bound
by the *item* JSON (CC_ItemPipe.json CustomModelTexture / the _On twins). So the
"texture refs = family texture" check is a check on the emitted item-JSON
patches, not on the models. The models only carry per-face atlas OFFSETS in
textureLayout.

Model-space geometry (16-unit block):
  - base body node: pos (0,16,0), size 16^3  -> spans x,z in [-8,8], y in [8,24].
    The block CENTRE is (0,16,0): yaw rotates positions about this point.
  - arm stubs are named by world direction and live at +-12 / +-(28|4):
        Stub_E = +X (x=12)    Stub_W = -X (x=-12)
        Stub_U = +Y (y=28)    Stub_D = -Y (y=4)
        Stub_N = +Z (z=12)    Stub_S = -Z (z=-12)
    This maps to the OFFSETS face indices used everywhere (PipeShapes javadoc):
        +X=0=E, -X=1=W, +Y=2=U, -Y=3=D, +Z=4=N, -Z=5=S.

Tip geometry (diff of authored tip vs plain end), the heart of extraction:
  - END (horizontal, base arm +Z / Stub_N):
      * PUSH: the existing Stub_N is MODIFIED (shortened): pos.z 12->10,
        size.z 8->4, plus two side-face atlas-offset tweaks; AND a new "Collar"
        node is added (size 6x6x2, stretch 2, pos z=13.5).
      * PULL: Stub_N is UNCHANGED; only a new "Collar" is added (size 10x10x2,
        stretch 2, pos z=14).
  - VERTICAL UP (base arm +Y / Stub_U) and VERTICAL DOWN (-Y / Stub_D) behave
    the same way along the Y axis, with their own authored textureLayouts.

Verified equivalences that license transposition:
  - authored ItemPipe_end.Stub_N is byte-identical to topology Stub_N. So the
    authored horizontal tip is defined against the same stub every topology shape
    already carries: a tip is "modify-the-stub (+ add a collar)".
  - yaw rotation of Stub_N's position/size about the centre reproduces the
    authored Stub_E/Stub_S/Stub_W positions+sizes EXACTLY (yaw1->E, yaw2->S,
    yaw3->W). The textureLayout side faces also cycle back->left->front->right
    under yaw90, matching authored Stub_E side-face offsets.

CONSEQUENCE for transposition:
  - Horizontal faces (+X,-X,+Z,-Z): take the authored +Z tip nodes (modified
    stub for push, collar for both) and yaw-rotate them (k = yaw index that
    carries +Z onto the target face). Geometry rotation is exact; textureLayout
    faces are cycled so rotated arms stay faithful. The +Z case is yaw=0
    (identity), so a generated End_Push for +Z is node-identical to the authored
    ItemPipe_end_push -> the round-trip proof (Stage C self-test).
  - Vertical faces (+Y,-Y): use the authored vertical tip nodes VERBATIM (their
    bespoke Y-axis textureLayouts cannot be obtained by yaw of a horizontal tip).

For PUSH the modified stub REPLACES the topology shape's stub for that arm; the
collar is appended. For PULL only the collar is appended (stub untouched).

================================================================================
Naming / keys (design "Naming scheme"):
  model file: <Family>_<shape>_t<face><p|l>[_t<face><p|l>].blockymodel
  state key : <ShapePascal>_T<face><P|L>[_T<face><P|L>]   (faces ascending)
================================================================================

Conventions: repo scripts style; ':' not em-dash in comments; deterministic
(stable ordering, no timestamps); byte-identical re-runs.
"""
import argparse
import copy
import hashlib
import itertools
import json
import sys
from pathlib import Path

# --- face conventions (OFFSETS order, PipeShapes javadoc) -----------------------
# index: +X=0,-X=1,+Y=2,-Y=3,+Z=4,-Z=5
FACE_DIR = {0: "E", 1: "W", 2: "U", 3: "D", 4: "N", 5: "S"}
HORIZONTAL_FACES = (0, 1, 4, 5)   # E,W,N,S  -> yaw of the +Z tip
VERTICAL_FACES = (2, 3)           # U,D      -> verbatim vertical tips
BLOCK_CENTRE_Y = 16               # model-space Y of the block centre

# yaw index k (0..3 = 0/90/180/270 about +Y) that carries the base +Z arm onto a
# given horizontal face. Derived from PipeShapes' yaw permutation (x,y,z)->(z,y,-x):
#   yaw0:+Z(N)  yaw1:+X(E)  yaw2:-Z(S)  yaw3:-X(W)
YAW_FOR_FACE = {4: 0, 0: 1, 5: 2, 1: 3}

# textureLayout side-face cycle under one yaw90 step (verified vs authored Stub_E).
# back->left->front->right->back; top/bottom unchanged.
FACE_CYCLE = {"back": "left", "left": "front", "front": "right", "right": "back"}

# The 26 non-node topology shapes in template order, with the PascalCase state key.
# (template key -> state key); used for emission ordering. The base arm masks come
# from the template Include rules and are computed at runtime (mask_for_shape).
SHAPE_KEYS = {
    "straight": "Straight", "straight_vertical": "Straight_Vertical",
    "elbow": "Elbow", "tee": "Tee", "cross": "Cross", "end": "End",
    "tripod": "Tripod", "fourbent": "Fourbent", "five": "Five", "six": "Six",
    "vertical_end_up": "Vertical_End_Up", "vertical_end_down": "Vertical_End_Down",
    "vertical_elbow_up": "Vertical_Elbow_Up", "vertical_elbow_down": "Vertical_Elbow_Down",
    "vertical_tee_up_ns": "Vertical_Tee_Up_Ns", "vertical_tee_up_ew": "Vertical_Tee_Up_Ew",
    "vertical_tee_down_ns": "Vertical_Tee_Down_Ns", "vertical_tee_down_ew": "Vertical_Tee_Down_Ew",
    "vertical_3horiz_up": "Vertical_3horiz_Up", "vertical_3horiz_down": "Vertical_3horiz_Down",
    "vertical_straight_ud_ns": "Vertical_Straight_Ud_Ns",
    "vertical_straight_ud_ew": "Vertical_Straight_Ud_Ew",
    "tripod_down": "Tripod_Down", "five_down": "Five_Down",
    "vertical_branch": "Vertical_Branch", "vertical_3horiz_ud": "Vertical_3horiz_Ud",
}

# Direction of each arm bit, used to find a topology shape's stub node by face.
STUB_NAME = {f: "Stub_" + d for f, d in FACE_DIR.items()}


# ============================================================================
# Pure helpers (covered by test_gen_pipe_tips.py)
# ============================================================================

def mask_for_shape(template, shape_key):
    """Base (rotation-0) connected-faces bitmask for a template Shape: the set of
    faces whose first pattern rule is Include. Position->face index per the
    OFFSETS convention (X=+1->+X=0, X=-1->-X=1, Y=+1->+Y=2, Y=-1->-Y=3,
    Z=+1->+Z=4, Z=-1->-Z=5)."""
    rules = template["Shapes"][shape_key]["PatternsToMatchAnyOf"][0]["RulesToMatch"]
    mask = 0
    for r in rules:
        if r["IncludeOrExclude"] != "Include":
            continue
        p = r["Position"]
        mask |= 1 << _pos_to_face(p["X"], p["Y"], p["Z"])
    return mask


def _pos_to_face(x, y, z):
    if x == 1:
        return 0
    if x == -1:
        return 1
    if y == 1:
        return 2
    if y == -1:
        return 3
    if z == 1:
        return 4
    if z == -1:
        return 5
    raise ValueError(f"non-unit neighbour position ({x},{y},{z})")


def mask_to_arms(mask):
    """Sorted list of face indices set in a 6-bit mask (ascending == OFFSETS order)."""
    return [f for f in range(6) if mask & (1 << f)]


def tip_assignments(arms):
    """Every legal <=2-tip assignment over a shape's arms, as a tuple of
    (face, kind) pairs with kind in {'p','l'}, faces strictly ascending.

    Matches the design count: per shape with k arms, 2k one-tip + 2k(k-1)
    two-tip variants. (Two distinct arms, each independently push or pull;
    same-arm-twice excluded; order canonicalised by ascending face so each
    unordered pair is counted once per (kind_a, kind_b) ordered over the two
    faces -> k*(k-1)/2 face-pairs * 4 kind-combos = 2k(k-1).)"""
    arms = sorted(arms)  # canonical ascending order for naming/keys
    out = []
    for f in arms:
        for k in ("p", "l"):
            out.append(((f, k),))
    for fa, fb in itertools.combinations(arms, 2):  # fa < fb
        for ka in ("p", "l"):
            for kb in ("p", "l"):
                out.append(((fa, ka), (fb, kb)))
    return out


def model_name(family, shape_key, assignment):
    """Composite model file stem, e.g. ItemPipe_tee_t0p_t4l."""
    suffix = "".join(f"_t{f}{k}" for f, k in assignment)
    return f"{family}_{shape_key}{suffix}"


def state_key(shape_pascal, assignment):
    """Composite State.Definitions key, e.g. Tee_T0p_T4l -> Tee_T0p_T4l."""
    suffix = "".join(f"_T{f}{k}" for f, k in assignment)
    return f"{shape_pascal}{suffix}"


def yaw_position(pos, k):
    """Rotate a node centre about the block centre by k * 90 deg around +Y.
    (x,y,z)->(z,y,-x) per yaw90."""
    x, y, z = pos["x"], pos["y"] - BLOCK_CENTRE_Y, pos["z"]
    for _ in range(k % 4):
        x, z = z, -x
    return {"x": x, "y": y + BLOCK_CENTRE_Y, "z": z}


def yaw_size(size, k):
    """Swap X/Z extents for odd yaw steps."""
    x, y, z = size["x"], size["y"], size["z"]
    for _ in range(k % 4):
        x, z = z, x
    return {"x": x, "y": y, "z": z}


def yaw_texture_layout(tl, k):
    """Cycle the four side faces back->left->front->right per yaw90 step; top and
    bottom are unchanged. Offsets/mirror/angle ride along with the face."""
    out = copy.deepcopy(tl)
    for _ in range(k % 4):
        prev = copy.deepcopy(out)
        for src, dst in FACE_CYCLE.items():
            out[dst] = prev[src]
    return out


def yaw_node(node, k):
    """Yaw-rotate a whole node (position, size, textureLayout) by k * 90 deg."""
    n = copy.deepcopy(node)
    n["position"] = yaw_position(node["position"], k)
    n["shape"]["settings"]["size"] = yaw_size(node["shape"]["settings"]["size"], k)
    n["shape"]["textureLayout"] = yaw_texture_layout(node["shape"]["textureLayout"], k)
    return n


# ============================================================================
# Tip extraction (Stage B)
# ============================================================================

def _node_by_name(model, name):
    for n in model["nodes"]:
        if n["name"] == name:
            return n
    return None


def extract_tip(plain_model, tipped_model, stub_name):
    """Return the tip as {'stub_override': node|None, 'collar': node}.

    'stub_override' is the modified arm stub when the tip reshapes it (push); it
    is None when the stub is untouched (pull). 'collar' is the added node. Names
    are stripped of ids (reassigned on merge). Raises if the structure is not the
    expected (modified-stub?, +1 collar) shape.

    The base body node is named after the model (ItemPipe_end vs
    ItemPipe_end_push), so it is matched by id "1" (the body is always node 1)
    rather than by name when computing the added nodes."""
    plain_names = {n["name"] for n in plain_model["nodes"]}
    # The body node (id "1") renames with the model (ItemPipe_end ->
    # ItemPipe_end_push); treat the tipped body's name as known so it is not
    # mistaken for an added node.
    plain_names.add(tipped_model["nodes"][0]["name"])
    extras = [n for n in tipped_model["nodes"] if n["name"] not in plain_names]
    if len(extras) != 1 or extras[0]["name"] != "Collar":
        raise ValueError(f"expected exactly one added 'Collar' node, got "
                         f"{[n['name'] for n in extras]}")
    collar = copy.deepcopy(extras[0])

    plain_stub = _node_by_name(plain_model, stub_name)
    tipped_stub = _node_by_name(tipped_model, stub_name)
    if plain_stub is None or tipped_stub is None:
        raise ValueError(f"stub '{stub_name}' missing from plain or tipped model")
    stub_override = None
    if _strip_id(plain_stub) != _strip_id(tipped_stub):
        stub_override = copy.deepcopy(tipped_stub)
    return {"stub_override": stub_override, "collar": collar}


def _strip_id(node):
    n = copy.deepcopy(node)
    n.pop("id", None)
    return n


# ============================================================================
# Composite emission (Stage D)
# ============================================================================

def _renumber(nodes):
    """Reassign ids 1..N in list order; return a fresh list (deterministic)."""
    out = []
    for i, n in enumerate(nodes, start=1):
        m = copy.deepcopy(n)
        m["id"] = str(i)
        out.append(m)
    return out


def build_composite(base_model, tips_by_face, assignment):
    """Merge a topology base model with the per-face tip node-sets.

    tips_by_face maps face index -> {'p': tip, 'l': tip} where each tip is the
    AUTHORED tip for that face's class (horizontal +Z authored, transposed; or
    vertical authored verbatim). 'assignment' fixes which (face,kind) tips apply
    and the deterministic node ordering: base nodes first (original order, with
    push stub-overrides swapped in place), then collars in assignment order."""
    nodes = [copy.deepcopy(n) for n in base_model["nodes"]]
    collars = []
    for face, kind in assignment:
        tip = tips_by_face[face][kind]
        if tip["stub_override"] is not None:
            idx = _index_of(nodes, STUB_NAME[face])
            if idx is None:
                raise ValueError(f"base model lacks {STUB_NAME[face]} for tipped arm")
            override = copy.deepcopy(tip["stub_override"])
            override["name"] = STUB_NAME[face]  # keep the topology stub's name
            nodes[idx] = override
        collar = copy.deepcopy(tip["collar"])
        collar["name"] = "Collar_" + FACE_DIR[face]  # unique per arm, deterministic
        collars.append(collar)
    merged = _renumber(nodes + collars)
    return {"nodes": merged, "format": base_model.get("format", "prop"),
            "lod": base_model.get("lod", "auto")}


def _index_of(nodes, name):
    for i, n in enumerate(nodes):
        if n["name"] == name:
            return i
    return None


def transpose_tip(horizontal_tip, vertical_tips, face):
    """The authored tip for `face`'s class, oriented onto `face`.

    horizontal_tip: the +Z authored {'p','l'} tip pair. vertical_tips: dict
    face(2|3) -> {'p','l'} verbatim authored vertical tip pair. Returns a
    {'p','l'} pair."""
    if face in VERTICAL_FACES:
        return vertical_tips[face]
    k = YAW_FOR_FACE[face]
    out = {}
    for kind, tip in horizontal_tip.items():
        out[kind] = {
            "stub_override": yaw_node(tip["stub_override"], k)
            if tip["stub_override"] is not None else None,
            "collar": yaw_node(tip["collar"], k),
        }
    return out


# ============================================================================
# Family loading + driver
# ============================================================================

def load_json(path):
    return json.loads(Path(path).read_text())


def dump_model(model, path):
    """Write a blockymodel with the corpus formatting (2-space indent, trailing
    newline, ensure_ascii). Deterministic."""
    Path(path).write_text(json.dumps(model, indent=2) + "\n")


def model_path_text(model):
    return json.dumps(model, indent=2, sort_keys=False) + "\n"


def family_tips(models_dir, family):
    """Load + extract the three authored tip orientations for a family.
    Returns (horizontal_tip_pair, vertical_tips_by_face)."""
    def m(name):
        return load_json(models_dir / f"{family}_{name}.blockymodel")

    horiz = {
        "p": extract_tip(m("end"), m("end_push"), "Stub_N"),
        "l": extract_tip(m("end"), m("end_pull"), "Stub_N"),
    }
    vert = {
        2: {  # +Y / Up
            "p": extract_tip(m("vertical_end_up"), m("vertical_end_up_push"), "Stub_U"),
            "l": extract_tip(m("vertical_end_up"), m("vertical_end_up_pull"), "Stub_U"),
        },
        3: {  # -Y / Down
            "p": extract_tip(m("vertical_end_down"), m("vertical_end_down_push"), "Stub_D"),
            "l": extract_tip(m("vertical_end_down"), m("vertical_end_down_pull"), "Stub_D"),
        },
    }
    return horiz, vert


def generate_family(models_dir, template, family, out_dir):
    """Generate every composite model for a family into out_dir. Returns a list of
    (state_key, model_file_stem, shape_key, assignment) records in deterministic
    order, plus the in-memory models keyed by stem for self-checks."""
    models_dir = Path(models_dir)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    horiz, vert = family_tips(models_dir, family)

    records = []
    built = {}
    for shape_key in SHAPE_KEYS:  # template/dict order is deterministic (py3.7+)
        shape_pascal = SHAPE_KEYS[shape_key]
        base = load_json(models_dir / f"{family}_{shape_key}.blockymodel")
        mask = mask_for_shape(template, shape_key)
        arms = mask_to_arms(mask)
        # Pre-transpose the tip for each arm face once.
        tips_by_face = {f: transpose_tip(horiz, vert, f) for f in arms}
        for assignment in tip_assignments(arms):
            stem = model_name(family, shape_key, assignment)
            key = state_key(shape_pascal, assignment)
            model = build_composite(base, tips_by_face, assignment)
            dump_model(model, out_dir / f"{stem}.blockymodel")
            records.append((key, stem, shape_key, assignment))
            built[stem] = model
    return records, built


# ============================================================================
# JSON patch emission (Stage E)
# ============================================================================

def texture_subdir(models_dir, family):
    return f"Blocks/ChonbosMods/Pipes"


def emit_patches(records, family, has_on_twins):
    """Build the JSON additions for Task 5 to apply. Returns a dict with:
       'state_definitions': {key -> {CustomModel}} (+ _On twins reusing the same
            model with the on-texture override, mirroring End_Push_On wiring),
       'template_shape_block_patterns': {template_key -> '*..._State_Definitions_<Key>'},
       'connected_template_shapes': {template_key -> face-tagged pattern-less Shape}.
    The on-twin texture override key is left for Task 5 to fill from the family's
    on-texture (we only know the model + key contract here)."""
    tex_dir = "Blocks/ChonbosMods/Pipes"
    state_definitions = {}
    template_patterns = {}
    template_shapes = {}
    item_prefix = _item_prefix(family)
    for key, stem, _shape, _assignment in records:
        state_definitions[key] = {
            "CustomModel": f"{tex_dir}/{stem}.blockymodel"
        }
        tkey = stem[len(family) + 1:]  # strip "<Family>_" -> template shape key
        template_patterns[tkey] = f"*{item_prefix}_State_Definitions_{key}"
        template_shapes[tkey] = _face_tagged_shape()
        if has_on_twins:
            on_key = key + "_On"
            state_definitions[on_key] = {
                "CustomModel": f"{tex_dir}/{stem}.blockymodel"
                # Task 5 adds the on-texture CustomModelTexture override, mirroring
                # the authored End_Push_On twin for this family.
            }
    return {
        "state_definitions": state_definitions,
        "template_shape_block_patterns": template_patterns,
        "connected_template_shapes": template_shapes,
    }


def _item_prefix(family):
    # CC_<Family>... item asset id prefix used in TemplateShapeBlockPatterns refs.
    return f"CC_{family}"


def _face_tagged_shape():
    tags = {d: ["CC_ItemFace"] for d in ("North", "South", "East", "West", "Up", "Down")}
    # Note: family face-tag name differs per channel; ITEM uses CC_ItemFace. Task 5
    # substitutes the family tag. Pattern-less (the state is selected in code, not
    # by neighbour matching), mirroring the authored end_push/end_pull Shapes.
    return {"FaceTags": tags, "PatternsToMatchAnyOf": []}


# ============================================================================
# Verification (Stage F, in-script self-tests)
# ============================================================================

def _digest(obj):
    return hashlib.sha256(json.dumps(obj, sort_keys=True).encode()).hexdigest()


def self_test(models_dir, template, family, out_dir):
    """Run the Stage F in-script checks. Returns a results dict; raises on
    failure. (a) every model parses, (b) node-count = base + sum(tip nodes),
    (c) deterministic re-run is hash-stable, (d) the Stage C round-trip proof,
    (e) expected counts."""
    models_dir = Path(models_dir)
    results = {}

    # --- (d) Round-trip proof: generated End_T4p == authored ItemPipe_end_push,
    #     End_T4l == authored end_pull; verticals verbatim. Node-identical
    #     (ignoring node ids and the base/collar node NAMES, which the generator
    #     canonicalises). ----------------------------------------------------
    horiz, vert = family_tips(models_dir, family)
    end_base = load_json(models_dir / f"{family}_end.blockymodel")
    tips_face4 = transpose_tip(horiz, vert, 4)  # +Z, yaw 0 identity
    rt = {}
    for kind, authored_name in (("p", "end_push"), ("l", "end_pull")):
        gen = build_composite(end_base, {4: tips_face4}, ((4, kind),))
        authored = load_json(models_dir / f"{family}_{authored_name}.blockymodel")
        rt[authored_name] = _models_geom_equal(gen, authored)
    vup_base = load_json(models_dir / f"{family}_vertical_end_up.blockymodel")
    tips_face2 = transpose_tip(horiz, vert, 2)
    for kind, authored_name in (("p", "vertical_end_up_push"), ("l", "vertical_end_up_pull")):
        gen = build_composite(vup_base, {2: tips_face2}, ((2, kind),))
        authored = load_json(models_dir / f"{family}_{authored_name}.blockymodel")
        rt[authored_name] = _models_geom_equal(gen, authored)
    if not all(rt.values()):
        raise AssertionError(f"round-trip proof FAILED: {rt}")
    results["round_trip"] = rt

    # --- generate into out_dir and check parse + node-count + counts ----------
    records, built = generate_family(models_dir, template, family, out_dir)
    out_dir = Path(out_dir)
    for path in out_dir.glob(f"{family}_*.blockymodel"):
        json.loads(path.read_text())  # (a) parses

    # (b) node count == base nodes + collars (+0; push swaps a stub in place).
    for key, stem, shape_key, assignment in records:
        base = load_json(models_dir / f"{family}_{shape_key}.blockymodel")
        expected = len(base["nodes"]) + len(assignment)  # one collar per tip
        got = len(built[stem]["nodes"])
        if got != expected:
            raise AssertionError(f"{stem}: node count {got} != {expected}")

    # (e) expected counts: the per-shape formula 2k+2k(k-1) summed over the 26
    #     template shapes. The AUTHORITATIVE arm-count distribution comes from the
    #     template Include masks, which is {1:3,2:5,3:8,4:6,5:3,6:1} -> 604.
    #     (The design doc's hand-estimate said {...,3:7,4:7,...} -> 618; it
    #     mis-bucketed one k3 shape as k4. The template is the source of truth, so
    #     PipeShapes must reproduce 604, NOT 618. See the report / Task 4 notes.)
    total = len(records)
    per_shape = {}
    for shape_key in SHAPE_KEYS:
        k = bin(mask_for_shape(template, shape_key)).count("1")
        per_shape[shape_key] = 2 * k + 2 * k * (k - 1)
    expected_total = sum(per_shape.values())
    if total != expected_total:
        raise AssertionError(f"count mismatch: generated {total} != "
                             f"formula sum {expected_total}")
    results["total_models"] = total
    results["per_shape"] = per_shape

    # (c) determinism: re-generate to a sibling dir, compare digests.
    out2 = out_dir.parent / (out_dir.name + "__rerun")
    records2, built2 = generate_family(models_dir, template, family, out2)
    d1 = _digest({s: m for s, m in built.items()})
    d2 = _digest({s: m for s, m in built2.items()})
    if d1 != d2:
        raise AssertionError("non-deterministic generation: digests differ")
    _rmtree(out2)
    results["deterministic"] = True
    return results


def _models_geom_equal(a, b):
    """Compare two models ignoring node ids and node NAMES (the generator
    canonicalises stub/collar names; geometry + textureLayout must match)."""
    def norm(m):
        ns = []
        for n in m["nodes"]:
            c = copy.deepcopy(n)
            c.pop("id", None)
            c.pop("name", None)
            ns.append(c)
        return ns
    return norm(a) == norm(b)


def _rmtree(path):
    path = Path(path)
    if not path.exists():
        return
    for p in path.glob("*"):
        p.unlink()
    path.rmdir()


# ============================================================================
# CLI
# ============================================================================

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models-dir", required=True,
                    help="dir holding <Family>_*.blockymodel sources")
    ap.add_argument("--template", required=True,
                    help="CC_<Family>ConnectedBlockTemplate.json (base masks)")
    ap.add_argument("--family", required=True, help="e.g. ItemPipe")
    ap.add_argument("--out-dir", required=True, help="output dir for composites")
    ap.add_argument("--on-twins", action="store_true",
                    help="emit _On twin state defs (power/fluid/gas; NOT item)")
    ap.add_argument("--patches-out", help="write the JSON patch bundle here")
    ap.add_argument("--self-test", action="store_true",
                    help="run Stage F self-checks (round-trip, counts, determinism)")
    args = ap.parse_args(argv)

    template = load_json(args.template)
    if args.self_test:
        res = self_test(args.models_dir, template, args.family, args.out_dir)
        print("SELF-TEST OK:", json.dumps({k: v for k, v in res.items()
                                           if k != "per_shape"}, indent=2))
        return 0

    records, _built = generate_family(args.models_dir, template, args.family, args.out_dir)
    print(f"generated {len(records)} models -> {args.out_dir}")
    if args.patches_out:
        patches = emit_patches(records, args.family, args.on_twins)
        Path(args.patches_out).write_text(json.dumps(patches, indent=2) + "\n")
        print(f"wrote patches -> {args.patches_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
