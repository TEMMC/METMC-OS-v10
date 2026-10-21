# METMC OS — compact hacker-style shell
export TERM="${TERM:-xterm-256color}"
export EDITOR="${EDITOR:-nano}"
export PAGER="${PAGER:-less}"
export HISTSIZE=2000
export HISTFILESIZE=4000
export HISTCONTROL=ignoredups:erasedups
shopt -s histappend checkwinsize 2>/dev/null || true
if [ -n "${BASH_VERSION:-}" ] && [ -n "${PS1:-}" ]; then
  PS1='\[\e[1;32m\]┌─[metmc@METMC-OS]\[\e[0m\]\n\[\e[1;32m\]└─[\w]\$ \[\e[0m\]'
fi
alias ll='ls -alF'
alias la='ls -A'
alias ..='cd ..'
alias ports='ss -tulpen'
alias myip='ip addr'
alias routes='ip route'
alias listen='ss -lntup'
alias update-help='echo "Package updates are user-initiated from METMC OS Settings."'
