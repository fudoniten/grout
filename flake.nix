{
  description = "Grout -- filler and interstitial media store";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
    nix-helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, nix-helpers }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        helpers = nix-helpers.legacyPackages.${system};

        grout = helpers.mkClojureBin {
          name = "org.fudo/grout";
          primaryNamespace = "grout.main";
          src = ./.;
        };

        migratusRunner = helpers.mkClojureBin {
          name = "org.fudo/grout-migrate";
          primaryNamespace = "grout.migrate";
          src = ./.;
        };

        grout-cli = pkgs.writeShellApplication {
          name = "grout-cli";
          runtimeInputs = [ pkgs.babashka ];
          text = ''
            exec bb ${./bin/grout-cli.bb} "$@"
          '';
        };

        # Resumable bulk uploader that drives grout-cli over a content root.
        # grout-cli is put on PATH so grout-bulk's default `--grout-cli
        # grout-cli` resolves without extra configuration.
        grout-bulk = pkgs.writeShellApplication {
          name = "grout-bulk";
          runtimeInputs = [ pkgs.babashka grout-cli ];
          text = ''
            exec bb ${./bin/grout-bulk.bb} "$@"
          '';
        };

        versionInfo = let
          gitCommit = self.rev or self.dirtyRev or "unknown";
          gitTimestamp = if self ? lastModified then
            let ts = toString self.lastModified;
            in "${builtins.substring 0 4 ts}${builtins.substring 4 2 ts}${builtins.substring 6 2 ts}"
          else
            "dev";
          versionTag = "${builtins.substring 0 7 gitCommit}-${gitTimestamp}";
        in { inherit gitCommit gitTimestamp versionTag; };

      in {
        packages = rec {
          default = grout;
          inherit grout migratusRunner grout-cli grout-bulk;

          deployContainer = let version = versionInfo;
          in helpers.deployContainers {
            name = "grout";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" version.versionTag ];
            # Keep ffmpeg/ffprobe on PATH for the intake/normalize pipeline.
            # nixpkgs' ffmpeg is built with --enable-nvenc and --enable-vaapi,
            # so hardware-accelerated intake transcode (grout.media.accel)
            # needs no special ffmpeg build: NVENC's encode library is injected
            # at runtime by the `nvidia` runtimeClass, and VAAPI needs a
            # userspace driver shipped here. libva-utils provides `vainfo` for
            # debugging VAAPI inside the pod. See the GPU wiring in
            # seattle-infra's deployment-grout.yaml.
            pathEnv = with pkgs; [ ffmpeg procps libva-utils ];
            env = {
              GIT_COMMIT = version.gitCommit;
              GIT_TIMESTAMP = version.gitTimestamp;
              VERSION = version.versionTag;
              FFMPEG_PATH = "${pkgs.ffmpeg}/bin/ffmpeg";
              FFPROBE_PATH = "${pkgs.ffmpeg}/bin/ffprobe";
              # VAAPI hardware acceleration (Intel). ffmpeg ships with
              # --enable-vaapi, but libva still needs a driver to talk to the
              # GPU; a bare container has none, so VAAPI init fails with "No VA
              # display found for device". Ship the Intel iHD driver and point
              # libva at it (NixOS normally does this via hardware.graphics,
              # absent in a plain image). Referencing the driver store path here
              # also pulls it into the image closure. Mirrors Pseudovision.
              # (NVENC needs none of this — its driver comes from the nvidia
              # runtimeClass.) For pre-Broadwell GPUs use intel-vaapi-driver +
              # LIBVA_DRIVER_NAME=i965.
              LIBVA_DRIVER_NAME = "iHD";
              LIBVA_DRIVERS_PATH = "${pkgs.intel-media-driver}/lib/dri";
            };
            entrypoint =
              let grout = self.packages."${system}".grout;
              in [ "${grout}/bin/grout" ];
            verbose = true;
          };

          deployMigrationContainer = let version = versionInfo;
          in helpers.deployContainers {
            name = "grout-migratus";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" version.versionTag ];
            env = {
              GIT_COMMIT = version.gitCommit;
              GIT_TIMESTAMP = version.gitTimestamp;
              VERSION = version.versionTag;
            };
            entrypoint =
              let migratus = self.packages."${system}".migratusRunner;
              in [ "${migratus}/bin/grout-migrate" ];
            verbose = true;
          };

          deployContainers = pkgs.writeShellScriptBin "deployContainers" ''
            set -euo pipefail
            echo "🚀 Deploying grout containers"
            echo "Version: ${versionInfo.versionTag}"
            echo "Commit: ${versionInfo.gitCommit}"
            echo "Timestamp: ${versionInfo.gitTimestamp}"
            echo ""

            echo "📦 Building and pushing primary container..."
            ${self.packages."${system}".deployContainer}/bin/deployContainers

            echo ""
            echo "📦 Building and pushing migration container..."
            ${self.packages."${system}".deployMigrationContainer}/bin/deployContainers

            echo ""
            echo "✅ Both containers deployed successfully!"
            echo "  Primary: registry.kube.sea.fudo.link/grout:${versionInfo.versionTag}"
            echo "  Migrate: registry.kube.sea.fudo.link/grout-migratus:${versionInfo.versionTag}"
          '';
        };

        checks = {
          clojureTests = helpers.mkClojureTests {
            name = "org.fudo/grout";
            src = ./.;
          };
          lint = pkgs.runCommand "grout-lint" {
            nativeBuildInputs = [ pkgs.clj-kondo ];
          } ''
            clj-kondo \
              --lint ${./src} ${./test} \
              --fail-level error
            touch $out
          '';
        };

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = [ (helpers.updateClojureDeps { aliases = [ "test" ]; }) ];
          };
          grout = pkgs.mkShell {
            packages = with pkgs; [ clojure jdk21 ffmpeg postgresql babashka ];
          };
        };

        apps = rec {
          default = deployContainers;
          deployContainers = {
            type = "app";
            program = "${self.packages."${system}".deployContainers}/bin/deployContainers";
          };
          deployContainer = {
            type = "app";
            program = "${self.packages."${system}".deployContainer}/bin/deployContainers";
          };
          deployMigrationContainer = {
            type = "app";
            program = "${self.packages."${system}".deployMigrationContainer}/bin/deployContainers";
          };
          migrate = {
            type = "app";
            program = "${migratusRunner}/bin/grout-migrate";
          };
          groutApp = {
            type = "app";
            program = "${grout}/bin/grout";
          };
          grout-cli = {
            type = "app";
            program = "${self.packages."${system}".grout-cli}/bin/grout-cli";
          };
          grout-bulk = {
            type = "app";
            program = "${self.packages."${system}".grout-bulk}/bin/grout-bulk";
          };
        };
      });
}
