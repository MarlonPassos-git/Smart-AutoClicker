// Replace SupercellFlash tools/test-tool/source/main.cpp with this file.
// It emits the SC2 object graph consumed by extract_coc_ui_icons.py.

#include <flash/flash.h>

#include <cstdint>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>

using namespace sc::flash;

namespace {
std::string clean_field(const SWFString& value) {
    std::string text = value.string();
    for (char& character : text) {
        if (character == '\t' || character == '\n') character = ' ';
    }
    return text;
}

std::string bytes_to_hex(const SWFVector<uint8_t, uint32_t>& bytes) {
    std::ostringstream output;
    for (uint8_t byte : bytes) {
        output << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(byte);
    }
    return output.str();
}

void print_exports(const SupercellSWF& swf) {
    for (const auto& item : swf.exports) {
        std::cout << "EXPORT\t" << item.id << '\t' << clean_field(item.name)
                  << '\t' << bytes_to_hex(item.hash) << '\n';
    }
}

void print_vertices(const SupercellSWF& swf) {
    for (const auto& shape : swf.shapes) {
        for (size_t index = 0; index < shape.commands.size(); ++index) {
            const auto& command = shape.commands[index];
            for (const auto& vertex : command.vertices) {
                std::cout << "VERTEX\t" << shape.id << '\t' << index << '\t'
                          << command.texture_index << '\t' << vertex.x << '\t' << vertex.y
                          << '\t' << vertex.u << '\t' << vertex.v << '\n';
            }
        }
    }
}

void print_movie_children(const MovieClip& movie) {
    for (size_t index = 0; index < movie.childrens.size(); ++index) {
        const auto& child = movie.childrens[index];
        std::cout << "CHILD\t" << movie.id << '\t' << index << '\t' << child.id << '\t'
                  << static_cast<int>(child.blend_mode) << '\t' << clean_field(child.name)
                  << '\n';
    }
}

void print_movie_frames(const MovieClip& movie) {
    for (size_t index = 0; index < movie.frames.size(); ++index) {
        const auto& frame = movie.frames[index];
        std::cout << "FRAME\t" << movie.id << '\t' << index << '\t'
                  << frame.elements_count << '\t' << clean_field(frame.label) << '\n';
    }
}

void print_movie_elements(const MovieClip& movie) {
    for (size_t index = 0; index < movie.frame_elements.size(); ++index) {
        const auto& element = movie.frame_elements[index];
        std::cout << "ELEMENT\t" << movie.id << '\t' << index << '\t'
                  << element.instance_index << '\t' << element.matrix_index << '\t'
                  << element.colorTransform_index << '\n';
    }
}

void print_movies(const SupercellSWF& swf) {
    for (const auto& movie : swf.movieclips) {
        std::cout << "MOVIE\t" << movie.id << '\t' << movie.bank_index << '\t'
                  << static_cast<int>(movie.frame_rate) << '\n';
        print_movie_children(movie);
        print_movie_frames(movie);
        print_movie_elements(movie);
    }
}

void print_matrices(const SupercellSWF& swf) {
    for (size_t bank = 0; bank < swf.matrixBanks.size(); ++bank) {
        const auto& matrices = swf.matrixBanks[bank].matrices;
        for (size_t index = 0; index < matrices.size(); ++index) {
            const auto& matrix = matrices[index];
            std::cout << "MATRIX\t" << bank << '\t' << index << '\t'
                      << matrix.a << '\t' << matrix.b << '\t' << matrix.c << '\t'
                      << matrix.d << '\t' << matrix.tx << '\t' << matrix.ty << '\n';
        }
    }
}
}

int main(int argc, char* argv[]) {
    if (argc != 2 || !std::filesystem::exists(argv[1])) {
        std::cerr << "Expected one existing ui.sc path\n";
        return 1;
    }
    SupercellSWF swf;
    swf.load(argv[1]);
    std::cout << "COUNTS\t" << swf.exports.size() << '\t' << swf.shapes.size() << '\t'
              << swf.movieclips.size() << '\t' << swf.textures.size() << '\n';
    print_exports(swf);
    print_vertices(swf);
    print_movies(swf);
    print_matrices(swf);
    return 0;
}
