/* = JSON Generator template =
[
  {
    'repeat(50)': {
      nodeId: '{{objectId()}}',
      typ: '{{random("ORGANIZATION", "PERSON", "GOVERNMENT")}}',
      name: function (tags) {
        switch (this.typ) {
          case "ORGANIZATION":
            return tags.company().toUpperCase() + " PTY LTD";
          case "PERSON":
            return tags.firstName().toUpperCase() + " " + tags.surname().toUpperCase();
          case "GOVERNMENT":
            return tags.country().toUpperCase() + " GOVT";
          default:
            return this.typ;
        }
      },
      importance: '{{integer(1, 5)}}'
    }
  }
]

[
  {
    'repeat(50)': {
      index: '{{index()}}',
      nodeId: '{{objectId()}}',
      score: '{{integer(1, 5)}}',
      typ: '{{random("ORGANIZATION", "PERSON", "FROM", "TO", "BCC")}}',
      name: function (tags) {
        return (this.typ === "ORGANIZATION") ?
          tags.company().toUpperCase() + " PTY LTD" :
          tags.firstName().toUpperCase() + " " + tags.surname().toUpperCase();
      }
    }
  }
]
*/

function bubbleGraph (graph, p) {
  let chart = d3.select("#bubble-chart")

  let format = d3.format(",d")
  let pack = d3.pack()
    .size([width, height])
    .padding(20)

  let packedNodes = pack(d3.hierarchy({children: graph.nodes}).sum(d => d.score))

  let nodeGroups = chart.selectAll("g")
    .data(packedNodes.children)

  let nodeG = nodeGroups.enter()
    .append("g")
    .attr('transform', d => 'translate(' + d.x + ',' + d.y + ')')
    .attr("id", d => `group-${d.data.nodeId}`)
    .style("cursor", "pointer")
    .on("mouseover", function (d) {
      d3.select(this).raise()
      circle.styles({
        "fill": d => d3.rgb(colourMap[d.data.typ]).darker(2),
        "fill-opacity": 0.2
      })
      d3.select(this).select("circle")
        .attr("r", d => d.r * 1.2)
        .styles({
          "fill": d => d3.rgb(colourMap[d.data.typ]).brighter(),
          "fill-opacity": 1
        })
      d3.selectAll(`.src-${d.data.nodeId}`)
        .style("stroke", "darkgray")
      d3.selectAll(`.target-${d.data.nodeId}`)
        .style("stroke", "darkgray")

      displayDataSidebar({
        title: `${d.data.extRef.name} (${d.data.typ})`,
        desc: `Score: ${d.data.score}, IDs: ${d.data.extRef.ids.reduce((acc, curr) => acc + ", " + curr)}`
      });

      graph.edges.map(edge => {
        if (edge.source === d.data.nodeId) {
          d3.select
          d3.select(`#group-${edge.target}`)
            .raise()
            .select("circle")
              .styles({
                "fill": d => colourMap[d.data.typ],
                "fill-opacity": 1
              })
        } else if (edge.target === d.data.nodeId) {
          d3.select(`#group-${edge.source}`)
            .raise()
            .select("circle")
              .styles({
                "fill": d => colourMap[d.data.typ],
                "fill-opacity": 1
              })
        }
      })
    })
    .on("mouseout", function (d) {
      nodeG.lower()
      circle.styles({
          "fill": d => colourMap[d.data.typ],
          "fill-opacity": 1
        })
        .attr("r", d => d.r)
      hideDataSidebar()
      lines.style("stroke", "none")
    })

  let circle = nodeG.append("circle")
    .attr("r", d => d.r)
    .attr("id", d => `circ-${d.data.nodeId}`)
    .style("fill", d => colourMap[d.data.typ])

  let labels = nodeG.append("text")
    .text(d => d.data.extRef.name)
    .attr("fill", "white")
    .attr("font-family", "Verdana")
    .attr('text-anchor', "middle")
    .attr("alignment-baseline", "middle")
    .attr("font-size", function(d) {
      return Math.min(1.5 * d.r, (1.5 * d.r - 8) / this.getComputedTextLength() * 24) + "px";
    })

  //d3plus.textwrap().container(d3.select("text")).resize(true).draw()

  let nodeEdges = graph.edges.map(edge => Object.assign({}, edge, {
    x1: d3.select(`#circ-${edge.source}`).data()[0].x,
    y1: d3.select(`#circ-${edge.source}`).data()[0].y,
    x2: d3.select(`#circ-${edge.target}`).data()[0].x,
    y2: d3.select(`#circ-${edge.target}`).data()[0].y
  }))

  let lines = chart.selectAll(".link")
    .data(nodeEdges)
    .enter().append("line")
    .attr("class", d => `link src-${d.source} target-${d.target}`)
    .styles({"stroke-width": 2, "z-index": 1})

  lines.attrs(d => (({ x1, y1, x2, y2 }) => ({ x1, y1, x2, y2 }))(d))
}
