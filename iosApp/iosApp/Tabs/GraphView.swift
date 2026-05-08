// SPDX-License-Identifier: MIT
//
// Force-directed visualization of every known destination, with the
// local identity pinned at centre. IosTopology computation lives in
// commonMain (`shared/.../graph/GraphTopology.kt`) and is shared with
// the Android renderer; this file ports the Android ForceLayout math
// + Compose Canvas drawing to SwiftUI's Canvas + TimelineView animation
// loop.
//
// The simulation runs for ~6 seconds after each topology rebuild, then
// stops to save battery. Pan via DragGesture, zoom via
// MagnificationGesture (multiplicative). Labels render at screen
// scale (not the canvas's transformed scale) so a zoomed-in graph
// stays legible at 6x without overlapping labels.
//
// IosTopology rebuild is throttled the same way Android does: a stable
// "topology key" derived from hash list + role + displayName +
// favorite + appName. RSSI / lastSeen jitter does NOT trigger
// rebuild — that would restart the 6s simulation on every announce
// on a chatty mesh.

import Shared
import SwiftUI

struct GraphView: View {

    @EnvironmentObject private var store: ReticulumStore

    @State private var canvasSize: CGSize = CGSize(width: 800, height: 800)
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var lastPan: CGSize = .zero

    var body: some View {
        VStack(spacing: 0) {
            legendBar
                .padding(.horizontal, 12)
                .padding(.top, 8)

            GeometryReader { geo in
                graphCanvas(size: geo.size)
                    .onAppear { canvasSize = geo.size }
                    .onChange(of: geo.size) { _, newSize in canvasSize = newSize }
            }
            .padding(8)
        }
        .navigationTitle("Graph")
        .overlay {
            if store.allDestinations.isEmpty {
                Text("Connect a transport on Settings to populate the graph.")
                    .foregroundStyle(.secondary)
                    .padding()
            }
        }
    }

    // MARK: - Legend bar

    private var legendBar: some View {
        HStack(spacing: 12) {
            Legend(color: .accentColor, label: "LXMF favorite")
            Legend(color: .accentColor.opacity(0.5), label: "LXMF other")
            Legend(color: .secondary, label: "Non-LXMF")
            Legend(color: relayColor, label: "Relay")
            Spacer()
            Text(String(format: "%.1fx", scale))
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
        }
        .font(.caption)
    }

    /// Material 3's tertiary color is what Android uses for relay
    /// glyphs; iOS doesn't have a direct equivalent so we approximate
    /// with a tinted purple that stands out from the LXMF accent.
    private var relayColor: Color { Color(red: 0.45, green: 0.30, blue: 0.65) }

    // MARK: - Animated canvas

    private func graphCanvas(size: CGSize) -> some View {
        let topology = buildSwiftIosTopology()
        // TimelineView ticks every animation frame for 6 seconds after
        // simulation start, then settles. The view stops requesting
        // updates once the simulation budget is spent — saves battery
        // on a backgrounded tab.
        return TimelineView(.animation) { _ in
            // Rebuild simulation when topology key changes. Holding
            // the layout in a static cache keyed on (topology hash,
            // canvas size) gives stable behaviour across the per-
            // frame view recompositions TimelineView triggers.
            let layout = currentLayout(topology: topology, canvasSize: size)
            Canvas { ctx, _ in
                drawGraph(layout: layout, in: &ctx, size: size)
            }
        }
        .gesture(
            SimultaneousGesture(
                MagnificationGesture()
                    .onChanged { value in scale = (lastScale * value).clamped(to: 0.3...6.0) }
                    .onEnded { _ in lastScale = scale },
                DragGesture()
                    .onChanged { value in
                        pan = CGSize(
                            width: lastPan.width + value.translation.width,
                            height: lastPan.height + value.translation.height
                        )
                    }
                    .onEnded { _ in lastPan = pan }
            )
        )
    }

    // MARK: - Drawing

    /// Renders edges + nodes inside the pan/scale transform, then
    /// labels OUTSIDE the transform (screen-scale fonts) so a zoomed
    /// graph stays legible. Same separation as the Android renderer.
    private func drawGraph(layout: SwiftForceLayout, in ctx: inout GraphicsContext, size: CGSize) {
        let pivot = CGPoint(x: size.width / 2, y: size.height / 2)
        let outline: Color = .secondary.opacity(0.5)
        let onSurface: Color = .primary

        // World-space rendering (edges + node circles) — apply
        // translate + scale around the canvas centre.
        var inner = ctx
        inner.translateBy(x: pan.width, y: pan.height)
        inner.translateBy(x: pivot.x, y: pivot.y)
        inner.scaleBy(x: scale, y: scale)
        inner.translateBy(x: -pivot.x, y: -pivot.y)

        for edge in layout.edges {
            guard let a = layout.node(id: edge.from), let b = layout.node(id: edge.to) else { continue }
            var path = Path()
            path.move(to: CGPoint(x: CGFloat(a.x), y: CGFloat(a.y)))
            path.addLine(to: CGPoint(x: CGFloat(b.x), y: CGFloat(b.y)))
            inner.stroke(path, with: .color(outline), lineWidth: 1.5 / scale)
        }

        for node in layout.nodes {
            let center = CGPoint(x: CGFloat(node.x), y: CGFloat(node.y))
            let radius = CGFloat(node.data.radius)
            let circle = Path(ellipseIn: CGRect(
                x: center.x - radius, y: center.y - radius,
                width: radius * 2, height: radius * 2
            ))
            inner.fill(circle, with: .color(node.data.color))
            inner.stroke(circle, with: .color(onSurface), lineWidth: 1.2 / scale)
        }

        // Screen-space labels — project each node's world position
        // through the same affine as the inner context, then draw at
        // native scale so 6x zoom doesn't make labels overlap.
        for node in layout.nodes {
            if node.data.radius < 6 { continue }
            let sx = pivot.x + (CGFloat(node.x) - pivot.x) * scale + pan.width
            let sy = pivot.y + (CGFloat(node.y) - pivot.y) * scale + pan.height
            let labelOffset = CGFloat(node.data.radius) * scale + 4
            let text = Text(node.data.label).font(.caption2)
            ctx.draw(text, at: CGPoint(x: sx, y: sy + labelOffset), anchor: .top)
        }
    }

    // MARK: - Layout cache

    /// Memoise the simulation by topology key + canvas size — same
    /// throttling Android does. SwiftUI redraws re-fetch the same
    /// instance and just keep stepping it.
    private func currentLayout(topology: IosTopology, canvasSize: CGSize) -> SwiftForceLayout {
        let key = topology.cacheKey(canvasSize: canvasSize)
        if let cached = SwiftForceLayout.cache[key] {
            cached.stepIfNeeded()
            return cached
        }
        let layout = SwiftForceLayout(topology: topology, canvasSize: canvasSize)
        SwiftForceLayout.cache.removeAll()
        SwiftForceLayout.cache[key] = layout
        layout.stepIfNeeded()
        return layout
    }

    // MARK: - IosTopology bridging

    /// Calls the shared Kotlin `buildTopology` then wraps each node /
    /// edge in a Swift-native value type. Snapshotting once keeps the
    /// animation loop fast — accessing Kotlin instance properties
    /// crosses the K/N bridge, and at 60 fps with 50 nodes that's
    /// 3000+ bridge crossings per second otherwise.
    private func buildSwiftIosTopology() -> IosTopology {
        let kt = GraphTopologyKt.buildTopology(
            destinations: store.allDestinations,
            ourHash: store.ourDestHash,
            maxLeaves: 60
        )
        let nodes: [IosTopology.Node] = kt.nodes.map { kn in
            let role: IosTopology.Role = {
                switch kn.role {
                case NodeRole.me:    return .me
                case NodeRole.relay: return .relay
                case NodeRole.leaf:  return .leaf
                default: return .leaf
                }
            }()
            return IosTopology.Node(
                id: kn.id,
                role: role,
                displayName: kn.displayName,
                isFavorite: kn.isFavorite,
                appName: kn.appName
            )
        }
        let edges: [IosTopology.Edge] = kt.edges.map {
            IosTopology.Edge(from: $0.from, to: $0.to)
        }
        return IosTopology(nodes: nodes, edges: edges)
    }
}

// MARK: - Native IosTopology (renderer-friendly)

/// Swift-native value-type mirror of the Kotlin IosTopology types.
/// Snapshotting from Kotlin once per topology rebuild — not per frame
/// — keeps the animation loop bridge-free.
struct IosTopology: Hashable {
    enum Role { case me, relay, leaf }
    struct Node: Hashable {
        let id: String
        let role: Role
        let displayName: String
        let isFavorite: Bool
        let appName: String?
    }
    struct Edge: Hashable {
        let from: String
        let to: String
    }
    let nodes: [Node]
    let edges: [Edge]

    /// IosTopology key — derived from just the parts that change the
    /// graph SHAPE. We deliberately exclude RSSI / lastSeen so a
    /// chatty mesh doesn't restart the 6s simulation on every
    /// announce.
    func cacheKey(canvasSize: CGSize) -> String {
        let nodePart = nodes
            .map { "\($0.id)|\($0.role)|\($0.displayName)|\($0.isFavorite)|\($0.appName ?? "")" }
            .joined(separator: ";")
        let edgePart = edges.map { "\($0.from)→\($0.to)" }.joined(separator: ";")
        return "\(nodePart)#\(edgePart)#\(Int(canvasSize.width))x\(Int(canvasSize.height))"
    }
}

// MARK: - Force layout (Swift port of androidApp/.../graph/ForceLayout.kt)

/// Coulomb repulsion + Hooke spring attraction + weak gravity +
/// velocity damping each step. ~50 nodes practical max — matches
/// Android. The simulation budget is 6 seconds; after that
/// `stepIfNeeded()` becomes a no-op so a backgrounded tab doesn't
/// keep the layout pinned at "still moving".
final class SwiftForceLayout {
    /// One-entry cache of the most recent simulation, keyed by
    /// topology+canvas-size. SwiftUI may rebuild the GraphView on
    /// every animation tick; without this we'd get a fresh
    /// SwiftForceLayout each frame and the simulation would restart.
    static var cache: [String: SwiftForceLayout] = [:]

    let nodes: [LayoutNode]
    let edges: [IosTopology.Edge]
    private let canvasSize: CGSize
    private let createdAt = Date.timeIntervalSinceReferenceDate
    private var lastStepAt: TimeInterval = 0

    private let repulsion: Float = 22000
    private let springLength: Float = 140
    private let springStiffness: Float = 0.04
    private let gravity: Float = 0.005
    private let damping: Float = 0.85
    private let maxStep: Float = 12

    private let byId: [String: LayoutNode]

    init(topology: IosTopology, canvasSize: CGSize) {
        self.canvasSize = canvasSize
        self.edges = topology.edges
        let cx = Float(canvasSize.width / 2)
        let cy = Float(canvasSize.height / 2)
        self.nodes = topology.nodes.map { tn -> LayoutNode in
            // Deterministic per-id PRNG so the same topology key
            // produces the same starting layout — visual stability
            // across recompositions.
            let seed = UInt64(bitPattern: Int64(tn.id.hashValue))
            var rng = SplitMix64(seed: seed)
            let angle = Float(rng.nextDouble()) * 6.2831853
            let radius = 50 + Float(rng.nextDouble()) * 200
            let fixed = (tn.role == .me)
            return LayoutNode(
                data: GraphNode(
                    id: tn.id,
                    label: String(tn.displayName.prefix(14)),
                    color: SwiftForceLayout.colorFor(node: tn),
                    radius: SwiftForceLayout.radiusFor(node: tn),
                    fixed: fixed
                ),
                x: cx + radius * cos(angle),
                y: cy + radius * sin(angle),
                vx: 0, vy: 0,
                fixed: fixed
            )
        }
        self.byId = Dictionary(uniqueKeysWithValues: self.nodes.map { ($0.data.id, $0) })
    }

    func node(id: String) -> LayoutNode? { byId[id] }

    /// Steps once if the 6-second simulation budget hasn't elapsed.
    /// Called from the TimelineView's per-frame closure; idempotent
    /// after the budget is spent so a sleeping tab doesn't burn CPU.
    func stepIfNeeded() {
        let now = Date.timeIntervalSinceReferenceDate
        if now - createdAt > 6 { return }
        let dt = now - lastStepAt
        if dt < 0.008 { return }  // ~120fps cap
        lastStepAt = now
        step()
    }

    private func step() {
        let cx = Float(canvasSize.width / 2)
        let cy = Float(canvasSize.height / 2)

        // Coulomb repulsion (every pair)
        for i in 0..<nodes.count {
            let a = nodes[i]
            for j in (i + 1)..<nodes.count {
                let b = nodes[j]
                let dx = a.x - b.x
                let dy = a.y - b.y
                let dist2 = max(dx * dx + dy * dy, 1)
                let force = repulsion / dist2
                let dist = sqrtf(dist2)
                let fx = (dx / dist) * force
                let fy = (dy / dist) * force
                if !a.fixed { a.vx += fx; a.vy += fy }
                if !b.fixed { b.vx -= fx; b.vy -= fy }
            }
        }

        // Hooke springs along edges
        for e in edges {
            guard let a = byId[e.from], let b = byId[e.to] else { continue }
            let dx = b.x - a.x
            let dy = b.y - a.y
            let dist = max(sqrtf(dx * dx + dy * dy), 0.0001)
            let displacement = dist - springLength
            let fx = (dx / dist) * displacement * springStiffness
            let fy = (dy / dist) * displacement * springStiffness
            if !a.fixed { a.vx += fx; a.vy += fy }
            if !b.fixed { b.vx -= fx; b.vy -= fy }
        }

        // Gravity towards centre + damping + integration
        for n in nodes {
            if n.fixed { n.vx = 0; n.vy = 0; continue }
            n.vx += (cx - n.x) * gravity
            n.vy += (cy - n.y) * gravity
            n.vx *= damping
            n.vy *= damping
            let sx = max(-maxStep, min(maxStep, n.vx))
            let sy = max(-maxStep, min(maxStep, n.vy))
            n.x += sx
            n.y += sy
        }
    }

    private static func colorFor(node: IosTopology.Node) -> Color {
        switch node.role {
        case .me: return .accentColor
        case .relay: return Color(red: 0.45, green: 0.30, blue: 0.65)
        case .leaf:
            if node.appName == "lxmf.delivery" && node.isFavorite { return .accentColor }
            if node.appName == "lxmf.delivery" { return .accentColor.opacity(0.5) }
            return .secondary
        }
    }

    private static func radiusFor(node: IosTopology.Node) -> Float {
        switch node.role {
        case .me: return 14
        case .relay: return 10
        case .leaf: return node.isFavorite ? 11 : 7
        }
    }
}

/// A graph node ready for rendering: just label + color + radius +
/// pin-state. The renderer doesn't see IosTopologyNode directly.
struct GraphNode {
    let id: String
    let label: String
    let color: Color
    let radius: Float
    let fixed: Bool
}

/// Mutable position + velocity for one simulated node. Reference
/// type because the simulation mutates x/y/vx/vy in place each step
/// and we want every byId lookup to see the same instance.
final class LayoutNode {
    let data: GraphNode
    var x: Float
    var y: Float
    var vx: Float
    var vy: Float
    let fixed: Bool

    init(data: GraphNode, x: Float, y: Float, vx: Float, vy: Float, fixed: Bool) {
        self.data = data
        self.x = x
        self.y = y
        self.vx = vx
        self.vy = vy
        self.fixed = fixed
    }
}

// MARK: - Helpers

private struct Legend: View {
    let color: Color
    let label: String

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 10, height: 10)
            Text(label).font(.caption)
        }
    }
}

private extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        return min(max(self, limits.lowerBound), limits.upperBound)
    }
}

/// SplitMix64 PRNG seeded by node id. Same starting positions for
/// the same topology id → visually stable layout across SwiftUI
/// recompositions. (Foundation's Swift `Random` isn't seedable.)
private struct SplitMix64 {
    private var state: UInt64
    init(seed: UInt64) { state = seed &+ 0x9E3779B97F4A7C15 }
    mutating func next() -> UInt64 {
        state = state &+ 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z &>> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z &>> 27)) &* 0x94D049BB133111EB
        return z ^ (z &>> 31)
    }
    mutating func nextDouble() -> Double {
        // Top 53 bits → uniform [0, 1)
        let bits = next() &>> 11
        return Double(bits) / Double(1 &<< 53)
    }
}
