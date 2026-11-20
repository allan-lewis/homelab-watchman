{
  description = "Dev shell for homelab-watchman (Java 25 / Gradle / Spring Boot)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.jdk25
          pkgs.jdk21
          pkgs.gradle
          pkgs.doppler
        ];

        # jdk21/jdk25's setup hooks both want to export JAVA_HOME; force it
        # to jdk21 since Gradle 8.14 can't run itself on JDK 25 (only up to
        # 24). JDK 25 is surfaced separately, for toolchain resolution only,
        # via JAVA_HOME_25 + org.gradle.java.installations.fromEnv in
        # gradle.properties.
        JAVA_HOME = "${pkgs.jdk21}/lib/openjdk";
        JAVA_HOME_25 = "${pkgs.jdk25}/lib/openjdk";

        shellHook = ''
          echo "homelab-watchman dev shell: $(java -version 2>&1 | head -n1)"
        '';
      };
    };
}
