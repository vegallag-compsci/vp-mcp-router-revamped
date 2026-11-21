# One-time: create or update the issue labels this repo uses (needs `gh auth login`).
# Safe to re-run: --force updates colour and description of labels that exist.
param([string]$Repo = "vegallag-compsci/vp-mcp-router-revamped")

$labels = @(
    @{ name = "needs-probe";  color = "fbca04"; description = "Needs a live check in VP before a fix" },
    @{ name = "vp-behaviour"; color = "c5def5"; description = "VP's own behaviour; documented and worked around" },
    @{ name = "area:write";   color = "1d76db"; description = "Preview/apply, batch ops, revert, journal" },
    @{ name = "area:read";    color = "1d76db"; description = "Extractor, reads, selection, models" },
    @{ name = "area:lint";    color = "1d76db"; description = "vp_lint_diagram and the layout score" },
    @{ name = "area:layout";  color = "1d76db"; description = "vp_plan_layout, vp_layout, routing" },
    @{ name = "area:export";  color = "1d76db"; description = "Image and text export" },
    @{ name = "area:install"; color = "1d76db"; description = "Install, release and startup" },
    @{ name = "area:docs";    color = "1d76db"; description = "Skills, ADRs, tool descriptions" }
)
foreach ($label in $labels) {
    gh label create $label.name --repo $Repo --color $label.color --description $label.description --force
}
