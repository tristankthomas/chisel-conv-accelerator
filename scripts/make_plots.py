import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np

k = [1, 3, 5]
sw_int = [10807, 237458, 530622]
hw_int = [584, 607, 647]
sw_fp = [11172, 184016, 410161]
hw_fp = [1181, 1271, 1319]

speedup_int = [s/h for s, h in zip(sw_int, hw_int)]
speedup_fp  = [s/h for s, h in zip(sw_fp,  hw_fp)]

# --- Plot 1: Cycles vs Kernel Size ---
fig1, ax1 = plt.subplots(figsize=(9, 5))

ax1.plot(k, sw_int, 'o-',  color='#d62728', linewidth=2, markersize=7, label='SW Fixed Point')
ax1.plot(k, sw_fp,  '^--', color='#d62728', linewidth=2, markersize=7, label='SW Floating Point')
ax1.plot(k, hw_int, 'o-',  color='#1f77b4', linewidth=2, markersize=7, label='HW Fixed Point')
ax1.plot(k, hw_fp,  '^--', color='#1f77b4', linewidth=2, markersize=7, label='HW Floating Point')

ax1.set_yscale('log')
ax1.set_ylim(200, 3000000)
ax1.yaxis.set_major_formatter(ticker.FuncFormatter(lambda x, p: f'{int(x):,}'))
ax1.set_xlabel('Kernel Size $K$', fontsize=12)
ax1.set_ylabel('Cycles (log scale)', fontsize=12)
ax1.set_xticks(k)
ax1.tick_params(axis='both', labelsize=10)
ax1.legend(fontsize=10, loc='upper left')
ax1.grid(True, which='both', linestyle='--', linewidth=0.5, alpha=0.5)

plt.tight_layout()
plt.savefig('cycles_vs_kernel.pdf', bbox_inches='tight')

# --- Plot 2: Speedup Bar Chart ---
fig2, ax2 = plt.subplots(figsize=(9, 5))

x = np.arange(len(k))
width = 0.35

bars1 = ax2.bar(x - width/2, speedup_int, width, color='#1f77b4', label='Fixed Point')
bars2 = ax2.bar(x + width/2, speedup_fp,  width, color='#ff7f0e', label='Floating Point')

# add value labels on top of bars
for bar in bars1:
    ax2.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 5,
             f'{bar.get_height():.0f}$\\times$', ha='center', va='bottom', fontsize=9)
for bar in bars2:
    ax2.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 5,
             f'{bar.get_height():.0f}$\\times$', ha='center', va='bottom', fontsize=9)

ax2.set_xlabel('Kernel Size $K$', fontsize=12)
ax2.set_ylabel('Speedup vs Software', fontsize=12)
ax2.set_xticks(x)
ax2.set_xticklabels([f'$K={ki}$' for ki in k])
ax2.tick_params(axis='both', labelsize=10)
ax2.legend(fontsize=10)
ax2.grid(True, axis='y', linestyle='--', linewidth=0.5, alpha=0.5)

plt.tight_layout()
plt.savefig('speedup_bar.pdf', bbox_inches='tight')



# --- Plot 3: Performance Progression ---
versions = ['v1\nNaive', 'v2\nPipelined\nRequests', 'v2.1\n64-bit\nLoads', 'v3\nOutput\nStreaming', 'v4\nLine\nBuffer', 'v5\nSpatial x4']
cycles = [7329, 3171, 1893, 1380, 1103, 607]

fig3, ax3 = plt.subplots(figsize=(9, 5))

colors = ['#1f77b4'] * len(cycles)
colors[-1] = '#2ca02c'  # highlight v5 in green

bars = ax3.bar(versions, cycles, color=colors, edgecolor='white', linewidth=0.5, width=0.6)

# value labels on top
for bar in bars:
    ax3.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 80,
             f'{int(bar.get_height()):,}', ha='center', va='bottom', fontsize=9, fontweight='bold')

# theoretical minimum line
ax3.axhline(y=521, color='#d62728', linestyle='--', linewidth=1.5, label='Theoretical minimum (521 cycles)')

ax3.set_ylabel('Cycles', fontsize=12)
ax3.set_xlabel('Design Version', fontsize=12)
ax3.tick_params(axis='both', labelsize=10)
ax3.legend(fontsize=10, loc='upper right')
ax3.grid(True, axis='y', linestyle='--', linewidth=0.5, alpha=0.4)
ax3.set_ylim(0, 9000)
ax3.spines['top'].set_visible(False)
ax3.spines['right'].set_visible(False)

plt.tight_layout()
plt.savefig('performance_progression.pdf', bbox_inches='tight')


# --- Plot 4: Parallelism vs Cycles ---
parallelism = ['×1', '×2', '×4']
total_cycles = [1103, 627, 607]
compute_cycles = [1024, 521, 256]
memory_overhead = [c - comp for c, comp in zip(total_cycles, compute_cycles)]
# memory_overhead = [79, 115, 351] -- shows memory overhead growing as fraction

fig4, ax4 = plt.subplots(figsize=(9, 5))

x = np.arange(len(parallelism))
width = 0.5

p1 = ax4.bar(x, compute_cycles, width, color='#1f77b4', label='Compute cycles')
p2 = ax4.bar(x, memory_overhead, width, bottom=compute_cycles, color='#ff7f0e', label='Memory overhead')
ax4.axhline(y=521, color='#d62728', linestyle='--', linewidth=1.5, label='Memory transaction floor (521)')

ax4.set_ylabel('Cycles', fontsize=12)
ax4.set_xlabel('Parallelism Factor', fontsize=12)
ax4.set_xticks(x)
ax4.set_xticklabels(parallelism)
ax4.legend(fontsize=10)
ax4.grid(True, axis='y', linestyle='--', linewidth=0.5, alpha=0.4)
ax4.spines['top'].set_visible(False)
ax4.spines['right'].set_visible(False)

plt.tight_layout()
plt.savefig('parallelism_chart.pdf', bbox_inches='tight')



# --- Presentation plots: one per version, cumulative ---
pres_versions = ['v1\nNaive', 'v2\nPipelined\nRequests', 'v3\n64-bit\nLoads', 'v4\nOutput\nStreaming', 'v5\nLine\nBuffer', 'v6\nSpatial x4']
pres_cycles = [7329, 3171, 1893, 1380, 1103, 607]
pres_colors = ['#1f77b4', '#1f77b4', '#1f77b4', '#1f77b4', '#1f77b4', '#2ca02c']

for n in range(1, 7):
    fig, ax = plt.subplots(figsize=(11, 4))

    bars = ax.bar(
        pres_versions[:n],
        pres_cycles[:n],
        color=pres_colors[:n],
        edgecolor='white',
        linewidth=0.5,
        width=0.6
    )

    for bar in bars:
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 80,
                f'{int(bar.get_height()):,}', ha='center', va='bottom',
                fontsize=9, fontweight='bold')

    ax.axhline(y=521, color='#d62728', linestyle='--', linewidth=1.5,
               label='Theoretical minimum (521 cycles)')

    ax.set_ylabel('Cycles', fontsize=12)
    ax.set_xlabel('Design Version', fontsize=12)
    ax.tick_params(axis='both', labelsize=10)
    ax.legend(fontsize=10, loc='upper right')
    ax.grid(True, axis='y', linestyle='--', linewidth=0.5, alpha=0.4)
    ax.set_ylim(0, 9000)
    ax.set_xlim(-0.5, 5.5)  # keep x axis fixed across all 6 plots
    ax.set_xticks(range(6))
    ax.set_xticklabels(pres_versions, fontsize=9)
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    ax.set_title('Design Iteration Performance (Fixed Point, K=3)', fontsize=12, pad=10)

    plt.tight_layout()
    
    plt.savefig(f'pres_progression_v{n}.png', bbox_inches='tight', dpi=300)
    plt.close()



# --- Presentation: Parallelism chart ---
fig5, ax5 = plt.subplots(figsize=(6, 6))

x = np.arange(len(parallelism))
width = 0.5

ax5.bar(x, compute_cycles, width, color='#1f77b4', label='Compute cycles')
ax5.bar(x, memory_overhead, width, bottom=compute_cycles, color='#ff7f0e', label='Memory overhead')
ax5.axhline(y=521, color='#d62728', linestyle='--', linewidth=1.5, label='Memory transaction floor (521)')

ax5.set_ylabel('Cycles', fontsize=12)
ax5.set_xlabel('Parallelism Factor', fontsize=12)
ax5.set_xticks(x)
ax5.set_xticklabels(parallelism)
ax5.legend(fontsize=10)
ax5.grid(True, axis='y', linestyle='--', linewidth=0.5, alpha=0.4)
ax5.spines['top'].set_visible(False)
ax5.spines['right'].set_visible(False)
ax5.set_title('Spatial Parallelism (Fixed Point, K=3)', fontsize=12, pad=10)

plt.tight_layout()
plt.savefig('pres_parallelism.png', bbox_inches='tight', dpi=300)
plt.close()

# --- Presentation: Speedup bar chart ---
fig6, ax6 = plt.subplots(figsize=(9, 5))

x = np.arange(len(k))
width = 0.35

bars1 = ax6.bar(x - width/2, speedup_int, width, color='#1f77b4', label='Fixed Point')
bars2 = ax6.bar(x + width/2, speedup_fp,  width, color='#ff7f0e', label='Floating Point')

for bar in bars1:
    ax6.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 5,
             f'{bar.get_height():.0f}×', ha='center', va='bottom', fontsize=9, fontweight='bold')
for bar in bars2:
    ax6.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 5,
             f'{bar.get_height():.0f}×', ha='center', va='bottom', fontsize=9, fontweight='bold')

ax6.set_xlabel('Kernel Size $K$', fontsize=12)
ax6.set_ylabel('Speedup vs Software', fontsize=12)
ax6.set_xticks(x)
ax6.set_xticklabels([f'K={ki}' for ki in k])
ax6.tick_params(axis='both', labelsize=10)
ax6.legend(fontsize=10)
ax6.grid(True, axis='y', linestyle='--', linewidth=0.5, alpha=0.5)
ax6.spines['top'].set_visible(False)
ax6.spines['right'].set_visible(False)
ax6.set_title('Speedup vs Software Baseline', fontsize=12, pad=10)

plt.tight_layout()
plt.savefig('pres_speedup.png', bbox_inches='tight', dpi=300)
plt.close()

# --- Presentation: Cycles vs Kernel Size ---
fig7, ax7 = plt.subplots(figsize=(9, 5))

ax7.plot(k, sw_int, 'o-',  color='#d62728', linewidth=2, markersize=7, label='SW Fixed Point')
ax7.plot(k, sw_fp,  '^--', color='#d62728', linewidth=2, markersize=7, label='SW Floating Point')
ax7.plot(k, hw_int, 'o-',  color='#1f77b4', linewidth=2, markersize=7, label='HW Fixed Point')
ax7.plot(k, hw_fp,  '^--', color='#1f77b4', linewidth=2, markersize=7, label='HW Floating Point')

ax7.set_yscale('log')
ax7.set_ylim(200, 3000000)
ax7.yaxis.set_major_formatter(ticker.FuncFormatter(lambda x, p: f'{int(x):,}'))
ax7.set_xlabel('Kernel Size $K$', fontsize=12)
ax7.set_ylabel('Cycles (log scale)', fontsize=12)
ax7.set_xticks(k)
ax7.tick_params(axis='both', labelsize=10)
ax7.legend(fontsize=10, loc='upper left')
ax7.grid(True, which='both', linestyle='--', linewidth=0.5, alpha=0.5)
ax7.spines['top'].set_visible(False)
ax7.spines['right'].set_visible(False)
ax7.set_title('Software vs Hardware Scaling with Kernel Size', fontsize=12, pad=10)

plt.tight_layout()
plt.savefig('pres_kernel_scaling.png', bbox_inches='tight', dpi=300)
plt.close()