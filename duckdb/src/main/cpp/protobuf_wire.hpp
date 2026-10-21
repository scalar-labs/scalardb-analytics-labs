/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace scalardb_analytics {

enum class ProtobufWireType : uint8_t { VARINT = 0, FIXED64 = 1, LENGTH_DELIMITED = 2, FIXED32 = 5 };

struct ProtobufField {
	uint32_t number = 0;
	ProtobufWireType wire_type = ProtobufWireType::VARINT;
	uint64_t varint = 0;
	std::string bytes;
};

// Reads the top-level fields of a serialized protobuf message. It covers only the small status and
// health messages the extension decodes itself; malformed input ends the iteration.
class ProtobufReader {
public:
	explicit ProtobufReader(const std::string &data) : data(data), position(0) {
	}

	bool Next(ProtobufField &field) {
		uint64_t key;
		if (!ReadVarint(key)) {
			return false;
		}
		field.number = static_cast<uint32_t>(key >> 3);
		field.bytes.clear();
		field.varint = 0;
		switch (key & 0x7) {
		case 0:
			field.wire_type = ProtobufWireType::VARINT;
			return ReadVarint(field.varint);
		case 1:
			field.wire_type = ProtobufWireType::FIXED64;
			return Skip(8);
		case 2: {
			field.wire_type = ProtobufWireType::LENGTH_DELIMITED;
			uint64_t length;
			if (!ReadVarint(length) || length > data.size() - position) {
				return false;
			}
			field.bytes = data.substr(position, static_cast<size_t>(length));
			position += static_cast<size_t>(length);
			return true;
		}
		case 5:
			field.wire_type = ProtobufWireType::FIXED32;
			return Skip(4);
		default:
			return false;
		}
	}

private:
	bool ReadVarint(uint64_t &value) {
		value = 0;
		for (int shift = 0; shift < 64 && position < data.size(); shift += 7) {
			auto byte = static_cast<uint8_t>(data[position++]);
			value |= static_cast<uint64_t>(byte & 0x7F) << shift;
			if ((byte & 0x80) == 0) {
				return true;
			}
		}
		return false;
	}

	bool Skip(size_t length) {
		if (length > data.size() - position) {
			return false;
		}
		position += length;
		return true;
	}

	const std::string &data;
	size_t position;
};

} // namespace scalardb_analytics
