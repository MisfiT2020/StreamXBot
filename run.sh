#!/bin/bash
# FOR VPS

set -e

if command -v pacman &>/dev/null; then
    OS_TYPE="arch"
elif command -v apt &>/dev/null; then
    OS_TYPE="debian"
else
    echo "Unsupported OS: no supported package manager found."
    exit 1
fi

echo "Detected OS type: $OS_TYPE"

install_docker() {
    echo "Installing Docker..."

    if [[ "$OS_TYPE" == "arch" ]]; then
        sudo pacman -Syu --noconfirm docker
    elif [[ "$OS_TYPE" == "debian" ]]; then
        sudo apt update
        sudo apt install -y apt-transport-https ca-certificates curl software-properties-common

        curl -fsSL https://download.docker.com/linux/$(. /etc/os-release && echo "$ID")/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg

        echo \
          "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/$(. /etc/os-release && echo "$ID") \
          $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

        sudo apt update
        sudo apt install -y docker-ce docker-ce-cli containerd.io
    fi

    echo "Enabling Docker service..."
    sudo systemctl enable --now docker
    sudo usermod -aG docker "$USER"
}

install_docker_compose() {
    echo "Installing Docker Compose v2 plugin..."

    sudo mkdir -p /usr/local/lib/docker/cli-plugins
    curl -SL https://github.com/docker/compose/releases/download/v2.27.0/docker-compose-linux-x86_64 \
        -o docker-compose
    chmod +x docker-compose
    sudo mv docker-compose /usr/local/lib/docker/cli-plugins/docker-compose

    echo "Docker Compose version:"
    docker compose version
}

install_portainer() {
    echo "Installing Portainer..."

    docker volume create portainer_data || true

    docker run -d -p 9443:9443 \
        --name portainer \
        --restart=always \
        -v /var/run/docker.sock:/var/run/docker.sock \
        -v portainer_data:/data \
        portainer/portainer-ce:latest

    echo "Portainer is running at https://<your-server-ip>:9443"
}

install_python_312_arch() {
    echo "Installing Python 3.12 using yay..."
    yay -S --noconfirm python312

    echo "Creating virtual environment with Python 3.12..."
    /usr/bin/python3.12 -m venv .venv

    echo "Activating virtual environment..."
    source .venv/bin/activate

    echo "Python version in venv:"
    python --version
}

show_menu() {
    echo ""
    echo "Select an option:"
    echo "1) Install All"
    echo "2) Install Docker"
    echo "3) Install Portainer"
    echo "4) Exit"
    read -rp "Enter choice [1-4]: " choice

    case "$choice" in
        1)
            install_docker
            install_docker_compose
            install_portainer
            if [[ "$OS_TYPE" == "arch" ]]; then
                install_python_312_arch
            fi
            ;;
        2)
            install_docker
            install_docker_compose
            if [[ "$OS_TYPE" == "arch" ]]; then
                install_python_312_arch
            fi
            ;;
        3)
            install_portainer
            ;;
        4)
            echo "Exiting."
            exit 0
            ;;
        *)
            echo "Invalid choice."
            show_menu
            ;;
    esac
}

show_menu
